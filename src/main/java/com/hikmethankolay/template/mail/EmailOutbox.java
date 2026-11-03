package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.user.UserDeleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Queues and sends emails (transactional outbox).
 *
 * <ul>
 *   <li>{@link #enqueue} stores the email in the caller's transaction: it is sent only if the change
 *       that triggered it commits, and it is not lost if SMTP is down or the app stops.</li>
 *   <li>Right after the commit, the email is sent once from the request thread, so it usually
 *       arrives without delay.</li>
 *   <li>Emails that failed are retried by {@link #sendDue()} every {@code app.mail.outbox-poll-interval},
 *       with exponential backoff from {@code app.mail.retry-delay} (capped at one hour), until
 *       {@code app.mail.max-attempts} is reached; then an error is logged and the email is kept as a dead letter
 *       ({@code failed_at} set): no longer sent, but there to re-queue after a long outage, until
 *       {@code app.mail.dead-letter-retention} has passed. Any exception from the send counts as a failed attempt.</li>
 * </ul>
 *
 * <p>An email with an HTML body is sent as {@code multipart/alternative} with its text body as the fallback; one
 * without is sent as plain text.</p>
 *
 * <p>Sent emails are deleted, and dead letters after their retention, so links with tokens are not kept in the
 * database longer than needed; transactional dead letters lose their bodies at once, so re-queuing one drops it. A
 * deleted user's emails are deleted with the account. Metrics: {@code mail.outbox.sent}, {@code mail.outbox.failures},
 * {@code mail.outbox.dropped} and the gauge {@code mail.outbox.pending}.</p>
 */
@Service
public class EmailOutbox {

    private static final Logger log = LoggerFactory.getLogger(EmailOutbox.class);

    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private static final Duration QUEUE_SIZES_MAX_AGE = Duration.ofSeconds(30);

    /**
     * Allows one immediate send at a time. A request that sends holds database connections while
     * SMTP answers, so with a slow mail server parallel requests could drain the pool; the others
     * leave their email to {@link #sendDue()}.
     */
    private final Semaphore immediateSend = new Semaphore(1);

    private final OutboxEmailRepository outbox;

    private final JavaMailSender mailSender;

    private final TransactionTemplate newTransaction;

    private final AppProperties.Mail properties;

    private final MailRenderer renderer;

    private final EmailPreferenceService preferences;

    private final UnsubscribeTokens tokens;

    private final MeterRegistry meterRegistry;

    private final Counter sent;

    private final Counter failures;

    private final Counter dropped;

    private volatile QueueSizes queueSizes;

    /**
     * Creates the outbox and registers its metrics.
     *
     * @param outbox             outbox persistence
     * @param mailSender         sends the messages
     * @param transactionManager runs each send in its own transaction
     * @param properties         application properties holding the mail settings
     * @param renderer           renders emails in a category
     * @param preferences        tells whether a user receives an optional category
     * @param tokens             issues the unsubscribe tokens of optional emails
     * @param meterRegistry      registry for the outbox metrics
     */
    public EmailOutbox(
            OutboxEmailRepository outbox,
            JavaMailSender mailSender,
            PlatformTransactionManager transactionManager,
            AppProperties properties,
            MailRenderer renderer,
            EmailPreferenceService preferences,
            UnsubscribeTokens tokens,
            MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties.mail();
        this.renderer = renderer;
        this.preferences = preferences;
        this.tokens = tokens;
        this.meterRegistry = meterRegistry;
        this.sent =
                Counter.builder("mail.outbox.sent").description("Emails sent").register(meterRegistry);
        this.failures = Counter.builder("mail.outbox.failures")
                .description("Failed sending attempts")
                .register(meterRegistry);
        this.dropped = Counter.builder("mail.outbox.dropped")
                .description("Emails given up")
                .register(meterRegistry);
        Gauge.builder("mail.outbox.pending", this, self -> self.queueSizes().pending())
                .description("Emails waiting to be sent, broadcasts not included")
                .register(meterRegistry);
        Gauge.builder(
                        "mail.outbox.broadcast.pending",
                        this,
                        self -> self.queueSizes().broadcastPending())
                .description("Broadcast emails waiting to be sent")
                .register(meterRegistry);
    }

    /**
     * Returns the queue sizes for the gauges, counted at most every {@link #QUEUE_SIZES_MAX_AGE}: each count scans
     * the queue, and every scrape of every instance would run it.
     *
     * @return the latest counts
     */
    private QueueSizes queueSizes() {
        QueueSizes sizes = queueSizes;
        if (sizes == null || System.nanoTime() - sizes.countedAt() > QUEUE_SIZES_MAX_AGE.toNanos()) {
            sizes = new QueueSizes(
                    outbox.countByBroadcastIdIsNullAndFailedAtIsNull(),
                    outbox.countByBroadcastIdIsNotNullAndFailedAtIsNull(),
                    System.nanoTime());
            queueSizes = sizes;
        }
        return sizes;
    }

    /**
     * Emails waiting, as counted for the gauges.
     *
     * @param pending          waiting emails that belong to no broadcast
     * @param broadcastPending waiting broadcast emails
     * @param countedAt        {@link System#nanoTime()} of the count
     */
    record QueueSizes(long pending, long broadcastPending, long countedAt) {}

    /**
     * Queues an email in the current transaction and sends it once the transaction commits, unless
     * another immediate send is in progress; then the next {@link #sendDue()} run sends it.
     *
     * @param to      recipient address
     * @param subject subject line
     * @param text    plain-text body
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String to, String subject, String text) {
        enqueue(to, new RenderedMail(subject, text, null));
    }

    /**
     * Queues a rendered email in the current transaction and sends it once the transaction commits, unless another
     * immediate send is in progress; then the next {@link #sendDue()} run sends it.
     *
     * @param to   recipient address
     * @param mail the email, usually from {@link MailRenderer}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String to, RenderedMail mail) {
        queue(new OutboxEmail(to, mail.subject(), mail.text(), mail.html()));
    }

    /**
     * Queues a rendered email like {@link #enqueue(String, RenderedMail)}, but leaves the sending to the next
     * {@link #sendDue()} run. For emails an anonymous request sends only if the address has an account (a password
     * reset, a new verification link): sent from the request, the SMTP round trip would make those requests slower
     * and tell which addresses have accounts.
     *
     * @param to   recipient address
     * @param mail the email, usually from {@link MailRenderer}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueDeferred(String to, RenderedMail mail) {
        outbox.save(new OutboxEmail(to, mail.subject(), mail.text(), mail.html()));
    }

    /**
     * Renders and queues an email in a category, like {@link #enqueue(String, RenderedMail)}. An optional category
     * is skipped if the user turned it off (counted in {@code mail.outbox.suppressed}); otherwise the email gets the
     * user's unsubscribe link in its footer and {@code List-Unsubscribe} headers.
     *
     * @param userId   the recipient's user id
     * @param to       the recipient's address
     * @param category what the email is about
     * @param template template name without extension
     * @param subject  subject line
     * @param model    template variables
     * @return {@code false} if the user turned the category off and nothing was queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(
            long userId, String to, MailCategory category, String template, String subject, Map<String, Object> model) {
        return enqueue(null, userId, to, category, template, subject, model, preferences.isEnabled(userId, category));
    }

    /**
     * Queues one recipient of a broadcast like {@link #enqueue(long, String, MailCategory, String, String, Map)},
     * but leaves the sending to {@link #sendDue()}, which takes the other emails first: a large broadcast neither
     * holds up the request that queues it nor delays a password reset. Must run in a transaction.
     *
     * @param broadcastId the broadcast
     * @param userId      the recipient's user id
     * @param to          the recipient's address
     * @param category    the broadcast's optional category
     * @param template    template name without extension
     * @param subject     subject line
     * @param model       template variables
     * @param enabled     whether the user receives the category, read with the recipients to save a query each
     * @return {@code false} if the user turned the category off and nothing was queued
     */
    boolean enqueueBroadcast(
            long broadcastId,
            long userId,
            String to,
            MailCategory category,
            String template,
            String subject,
            Map<String, Object> model,
            boolean enabled) {
        return enqueue(broadcastId, userId, to, category, template, subject, model, enabled);
    }

    private boolean enqueue(
            Long broadcastId,
            long userId,
            String to,
            MailCategory category,
            String template,
            String subject,
            Map<String, Object> model,
            boolean enabled) {
        if (!enabled) {
            countSuppressed(category);
            return false;
        }
        String token = category.optional() ? tokens.issue(userId, category) : null;
        RenderedMail mail = renderer.render(template, subject, model, category, token);
        OutboxEmail email = new OutboxEmail(to, mail.subject(), mail.text(), mail.html(), token);
        if (broadcastId == null) {
            queue(email);
        } else {
            email.setBroadcastId(broadcastId);
            outbox.save(email);
        }
        return true;
    }

    /**
     * Stores an email and sends it once the transaction commits, unless another immediate send is in progress.
     *
     * @param email the email to store
     */
    private void queue(OutboxEmail email) {
        long id = outbox.save(email).getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!immediateSend.tryAcquire()) {
                    return;
                }
                try {
                    newTransaction.executeWithoutResult(
                            status -> outbox.lockById(id).ifPresent(email -> send(email, Instant.now())));
                } catch (RuntimeException ex) {
                    // The caller's transaction already committed, so it must not fail; sendDue retries the row.
                    log.warn(
                            "Immediate send of email {} failed, leaving it to the outbox: {}",
                            id,
                            ex.getClass().getSimpleName());
                } finally {
                    immediateSend.release();
                }
            }
        });
    }

    /** Sends the emails that are due. Runs on every instance; row locks keep them from sending the same email. */
    @Scheduled(fixedDelayString = "${app.mail.outbox-poll-interval}")
    public void sendDue() {
        // The interval is validated as AppProperties.Mail.outboxPollInterval at startup, before the scheduler starts.
        sendDue(Instant.now());
    }

    /**
     * Sends the emails that are due at a given time.
     *
     * @param now the time to compare due dates with; tests pass a later time to skip the backoff
     */
    public void sendDue(Instant now) {
        // One transaction per email: a batch idles in its transaction while SMTP works, and when PostgreSQL ends it
        // (idle_in_transaction_session_timeout) the emails already sent are sent again and failures are not counted.
        // Sends while emails are due, for at most one poll interval so the scheduler thread is not held forever.
        long deadline = System.nanoTime() + properties.outboxPollInterval().toNanos();
        Boolean found;
        do {
            found = newTransaction.execute(status -> {
                List<OutboxEmail> due = outbox.lockDue(now, 1);
                due.forEach(email -> send(email, now));
                return !due.isEmpty();
            });
        } while (Boolean.TRUE.equals(found) && System.nanoTime() - deadline < 0);
    }

    /**
     * Sends one locked email: deletes it on success, otherwise schedules a retry or keeps it as a dead letter.
     *
     * @param email the locked email
     * @param now   the current time
     */
    private void send(OutboxEmail email, Instant now) {
        if (unsubscribedSinceQueued(email)) {
            outbox.delete(email);
            return;
        }
        if (email.getBody().isEmpty()) {
            // A re-queued dead letter whose one-time links were cleared; the user has to request a new email.
            log.warn(
                    "Dropping email {} '{}': its body was cleared when it was given up",
                    email.getId(),
                    email.getSubject());
            outbox.delete(email);
            return;
        }
        try {
            if (email.getHtmlBody() == null) {
                mailSender.send(plainMessage(email));
            } else {
                mailSender.send(htmlMessage(email));
            }
            outbox.delete(email);
            sent.increment();
        } catch (RuntimeException ex) {
            // Not only MailException: anything else would roll back the attempt count and retry this row first forever.
            // The full text, which can name recipients, stays in last_error, deleted with the row; logs get the type.
            String error = ex instanceof MailException ? ex.getMessage() : ex.toString();
            failures.increment();
            email.recordFailure(error, now.plus(retryDelay(email.getAttempts())));
            if (email.getAttempts() >= properties.maxAttempts()) {
                email.markFailed(now);
                dropped.increment();
                log.error(
                        "Gave up sending email {} '{}' after {} attempts: {}",
                        email.getId(),
                        email.getSubject(),
                        email.getAttempts(),
                        ex.getClass().getSimpleName());
            } else {
                log.warn(
                        "Could not send email {} '{}' (attempt {}), retrying at {}: {}",
                        email.getId(),
                        email.getSubject(),
                        email.getAttempts(),
                        email.getNextAttemptAt(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    /** Deletes the dead letters older than {@code app.mail.dead-letter-retention}, once an hour. */
    @Scheduled(cron = "0 50 * * * *")
    @Transactional
    public void deleteExpiredDeadLetters() {
        int deleted = outbox.deleteFailedBefore(Instant.now().minus(properties.deadLetterRetention()));
        if (deleted > 0) {
            log.info("Deleted {} emails given up more than {} ago", deleted, properties.deadLetterRetention());
        }
    }

    /**
     * Deletes the emails to a deleted user's addresses in the deleting transaction, waiting or given up: nothing more
     * is sent to them, and dead letters do not keep their data.
     *
     * @param event the deleted user
     */
    @EventListener
    public void userDeleted(UserDeleted event) {
        outbox.deleteByRecipientIn(Stream.of(event.email(), event.pendingEmail())
                .filter(Objects::nonNull)
                .toList());
    }

    /**
     * Tells whether the recipient of an optional email turned its category off while it waited, e.g. during an SMTP
     * outage or behind a long queue; such an email is dropped and counted as suppressed.
     *
     * @param email the email about to be sent
     * @return {@code true} if it must not be sent
     */
    private boolean unsubscribedSinceQueued(OutboxEmail email) {
        Optional<UnsubscribeTokens.Claim> off = tokens.verify(email.getUnsubscribeToken())
                .filter(claim -> !preferences.isEnabled(claim.userId(), claim.category()));
        off.ifPresent(claim -> countSuppressed(claim.category()));
        return off.isPresent();
    }

    private void countSuppressed(MailCategory category) {
        Counter.builder("mail.outbox.suppressed")
                .description("Optional emails not sent because the recipient turned their category off")
                .tag("category", category.key())
                .register(meterRegistry)
                .increment();
    }

    /**
     * Builds a plain-text message.
     *
     * @param email the queued email
     * @return the message
     */
    private SimpleMailMessage plainMessage(OutboxEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email.getRecipient());
        message.setSubject(email.getSubject());
        message.setText(email.getBody());
        return message;
    }

    /**
     * Builds a message whose text and HTML bodies are {@code multipart/alternative} parts.
     *
     * @param email the queued email, with an HTML body
     * @return the message
     * @throws MailPreparationException if the message cannot be built, e.g. an invalid address; it counts as a
     *                                  failed attempt
     */
    private MimeMessage htmlMessage(OutboxEmail email) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(email.getRecipient());
            helper.setSubject(email.getSubject());
            helper.setText(email.getBody(), email.getHtmlBody());
            if (email.getUnsubscribeToken() != null) {
                // RFC 8058: mailbox providers POST "List-Unsubscribe=One-Click" to this URL, without cookies.
                String url = UriComponentsBuilder.fromUriString(properties.unsubscribeBaseUrl())
                        .path("/api/v1/mail/unsubscribe")
                        .queryParam("token", email.getUnsubscribeToken())
                        .toUriString();
                message.setHeader("List-Unsubscribe", "<" + url + ">");
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }
            return message;
        } catch (MessagingException ex) {
            throw new MailPreparationException("Could not build email " + email.getId(), ex);
        }
    }

    /**
     * Computes the wait before the next attempt: {@code retry-delay * 2^failedAttempts}, at most one hour.
     *
     * @param failedAttempts attempts made so far
     * @return the delay before the next attempt
     */
    private Duration retryDelay(int failedAttempts) {
        Duration delay = properties.retryDelay().multipliedBy(1L << Math.min(failedAttempts, 20));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }
}
