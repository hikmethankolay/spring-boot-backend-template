package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.mail.dto.BroadcastPreview;
import com.hikmethankolay.template.mail.dto.BroadcastRequest;
import com.hikmethankolay.template.mail.dto.BroadcastResponse;
import com.hikmethankolay.template.mail.dto.BroadcastResult;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Emails an announcement from the owner to many users, in an optional category only: there is no way to send
 * arbitrary email to an address.
 *
 * <ul>
 *   <li>Every broadcast uses the {@code broadcast/announcement} template; the body is plain text, escaped like any
 *       other value, so no markup an owner pastes reaches the email as HTML.</li>
 *   <li>The request checks the password, records and audits the broadcast and returns; the recipients are queued in
 *       the background, in batches by id ({@code app.mail.broadcast-batch-size}), each in its own transaction, through
 *       {@link EmailOutbox}, which skips users who turned the category off and adds their unsubscribe link. An error
 *       stops the queuing and marks the broadcast partial; the batches before it still go out. Each batch saves the
 *       last user id it queued with its emails, and {@code finished_at} is set when queuing ends.</li>
 *   <li>A broadcast cut off by a crash or stop is resumed after its saved last user id by {@link #resumeStalled()},
 *       once it has made no progress for five minutes, so no recipient is queued twice or skipped.</li>
 *   <li>Broadcast emails are sent by the outbox's scheduled runs, after the other emails waiting, so a large
 *       broadcast neither holds up a request nor delays a password reset.</li>
 *   <li>A new broadcast is refused while one is still being queued (or waits to be resumed), or less than a minute
 *       after the last one, across every instance.</li>
 * </ul>
 */
@Service
public class BroadcastService {

    /** Template every broadcast is rendered with: a title and plain paragraphs. */
    static final String TEMPLATE = "broadcast/announcement";

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    /** Key of the PostgreSQL advisory lock that serializes the start of broadcasts. */
    private static final long START_LOCK = 7_401_002L;

    /** Users in the comma-separated roles of the first parameter, verified unless the second is false. */
    private static final String AUDIENCE_FILTER =
            " WHERE u.role = ANY (string_to_array(?, ',')) AND (u.email_verified OR NOT ?)";

    private static final String AUDIENCE = " FROM users u" + AUDIENCE_FILTER;

    /** The audience minus the users who turned the category of the third parameter off. */
    private static final String RECIPIENTS = AUDIENCE + " AND NOT EXISTS (SELECT 1 FROM email_preferences p "
            + "WHERE p.user_id = u.id AND p.category = ? AND NOT p.enabled)";

    private final JdbcTemplate jdbc;

    private final EmailOutbox outbox;

    private final MailRenderer renderer;

    private final UserService users;

    private final AuditLog audit;

    private final MeterRegistry meterRegistry;

    private final TransactionTemplate newTransaction;

    private final int batchSize;

    private final TaskExecutor executor;

    /**
     * Creates the service.
     *
     * @param jdbc               database access
     * @param outbox             queues the emails
     * @param renderer           renders the preview
     * @param users              checks the owner's password
     * @param audit              records broadcasts
     * @param meterRegistry      registry for the recipient counter
     * @param transactionManager runs the start and each batch in its own transaction
     * @param properties         application properties holding the recipients queued per transaction
     * @param executor           Spring Boot's task executor, which queues broadcasts in the background and lets
     *                           them finish when the app stops ({@code spring.task.execution.shutdown})
     */
    public BroadcastService(
            JdbcTemplate jdbc,
            EmailOutbox outbox,
            MailRenderer renderer,
            UserService users,
            AuditLog audit,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            AppProperties properties,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.renderer = renderer;
        this.users = users;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = properties.mail().broadcastBatchSize();
        this.executor = executor;
    }

    /**
     * Tells how many users a broadcast would reach and how it reads, without sending anything.
     *
     * @param request the broadcast
     * @return the recipient count and the rendered text
     * @throws ApiException 400 if the category is unknown or transactional
     */
    @Transactional(readOnly = true)
    public BroadcastPreview preview(BroadcastRequest request) {
        MailCategory category = category(request);
        RenderedMail sample = renderer.render(
                TEMPLATE, request.subject(), model(request.title(), request.body()), category, "preview");
        return new BroadcastPreview(recipients(request, category), sample.subject(), sample.text());
    }

    /**
     * Records a broadcast and starts queuing it for every user in its audience, in the background.
     *
     * @param ownerId  the owner sending it
     * @param request  the broadcast, with the owner's current password
     * @return the broadcast id and the number of recipients expected
     * @throws ApiException 400 if the category is unknown or transactional or the password is wrong, 409 if a
     *                      broadcast is still being queued or started less than a minute ago, 429 if the IP or
     *                      account is locked after failed passwords, 503 if the failed-login counters cannot be
     *                      checked
     */
    public BroadcastResult send(long ownerId, BroadcastRequest request) {
        MailCategory category = category(request);
        // Wrong passwords count as failed logins, so a stolen access token cannot be used to guess the owner's
        // password.
        users.confirmPassword(ownerId, request.currentPassword());
        int recipients = recipients(request, category);
        long id =
                Objects.requireNonNull(newTransaction.execute(status -> start(ownerId, request, category, recipients)));
        executor.execute(() -> queue(id));
        return new BroadcastResult(id, recipients);
    }

    /**
     * Lists the latest broadcasts.
     *
     * @return up to 100 broadcasts, newest first
     */
    @Transactional(readOnly = true)
    public List<BroadcastResponse> history() {
        return jdbc.query(
                "SELECT id, category, subject, title, roles, verified_only, queued, suppressed, partial, created_by, "
                        + "created_at, finished_at FROM mail_broadcasts ORDER BY id DESC LIMIT 100",
                (row, i) -> {
                    long createdBy = row.getLong(10);
                    Long sender = row.wasNull() ? null : createdBy;
                    Timestamp finishedAt = row.getTimestamp(12);
                    return new BroadcastResponse(
                            row.getLong(1),
                            row.getString(2),
                            row.getString(3),
                            row.getString(4),
                            List.of(row.getString(5).split(",")),
                            row.getBoolean(6),
                            row.getInt(7),
                            row.getInt(8),
                            row.getBoolean(9),
                            sender,
                            row.getTimestamp(11).toInstant(),
                            finishedAt == null ? null : finishedAt.toInstant());
                });
    }

    private int recipients(BroadcastRequest request, MailCategory category) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*)" + RECIPIENTS, Integer.class, roles(request), request.verifiedOnly(), category.key());
        return count == null ? 0 : count;
    }

    /**
     * Records a new broadcast, unless one is still being queued or started less than a minute ago on any instance.
     *
     * @return the broadcast id
     */
    private long start(long ownerId, BroadcastRequest request, MailCategory category, int recipients) {
        // Held until the transaction ends, so two requests cannot both pass the check below.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", START_LOCK);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM mail_broadcasts WHERE created_at > now() - interval '1 minute'
                    OR finished_at IS NULL)
                """, Boolean.class))) {
            throw ApiException.conflict(
                    ProblemType.BROADCAST_IN_PROGRESS,
                    "Another broadcast is still being queued or started less than a minute ago.");
        }
        long id = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO mail_broadcasts (category, subject, title, body, roles, verified_only, created_by, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, now()) RETURNING id",
                Long.class,
                category.key(),
                request.subject(),
                request.title(),
                request.body(),
                roles(request),
                request.verifiedOnly(),
                ownerId));
        audit.record(
                AuditEvent.BROADCAST_SENT,
                ownerId,
                "broadcastId",
                id,
                "category",
                category.key(),
                "recipients",
                recipients);
        return id;
    }

    /**
     * Resumes, once a minute, the broadcasts whose queuing stopped: unfinished and without progress for five minutes,
     * because their instance crashed or was stopped. Each continues after the last user id saved with its last batch.
     * One instance at a time claims them, under the start lock.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void resumeStalled() {
        List<Long> stalled = newTransaction.execute(status -> {
            if (!Boolean.TRUE.equals(
                    jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, START_LOCK))) {
                return List.of();
            }
            // Claims them: their progress_at is now recent, so no other run takes them for five minutes.
            return jdbc.queryForList("""
                    UPDATE mail_broadcasts SET progress_at = now()
                    WHERE finished_at IS NULL AND progress_at < now() - interval '5 minutes' RETURNING id
                    """, Long.class);
        });
        for (long id : Objects.requireNonNull(stalled)) {
            log.warn("Resuming broadcast {}, whose queuing stopped", id);
            executor.execute(() -> queue(id));
        }
    }

    /**
     * Queues the recipients after the broadcast's saved progress in batches, then marks it finished; runs on the task
     * executor. Each batch saves its progress in its own transaction, only if no other run saved progress meanwhile,
     * so two runs never queue the same recipient.
     */
    private void queue(long id) {
        try {
            Map<String, Object> broadcast = jdbc.queryForMap(
                    "SELECT category, subject, title, body, roles, verified_only, last_user_id FROM mail_broadcasts "
                            + "WHERE id = ? AND finished_at IS NULL",
                    id);
            MailCategory category =
                    MailCategory.fromKey((String) broadcast.get("category")).orElseThrow();
            String subject = (String) broadcast.get("subject");
            String roles = (String) broadcast.get("roles");
            boolean verifiedOnly = (Boolean) broadcast.get("verified_only");
            Map<String, Object> model = model((String) broadcast.get("title"), (String) broadcast.get("body"));
            long lastId = ((Number) broadcast.get("last_user_id")).longValue();
            int queued = 0;
            boolean partial = false;
            try {
                while (true) {
                    List<Recipient> batch = jdbc.query(
                            // The preference comes with the recipient: no query per recipient.
                            "SELECT u.id, u.email, coalesce(p.enabled, true) FROM users u "
                                    + "LEFT JOIN email_preferences p ON p.user_id = u.id AND p.category = ?"
                                    + AUDIENCE_FILTER + " AND u.id > ? ORDER BY u.id LIMIT ?",
                            (row, i) -> new Recipient(row.getLong(1), row.getString(2), row.getBoolean(3)),
                            category.key(),
                            roles,
                            verifiedOnly,
                            lastId,
                            batchSize);
                    if (batch.isEmpty()) {
                        break;
                    }
                    long from = lastId;
                    Integer sent = newTransaction.execute(status -> {
                        int count = (int) batch.stream()
                                .filter(recipient -> outbox.enqueueBroadcast(
                                        id,
                                        recipient.id(),
                                        recipient.email(),
                                        category,
                                        TEMPLATE,
                                        subject,
                                        model,
                                        recipient.enabled()))
                                .count();
                        if (jdbc.update(
                                        "UPDATE mail_broadcasts SET last_user_id = ?, queued = queued + ?, "
                                                + "suppressed = suppressed + ?, progress_at = now() "
                                                + "WHERE id = ? AND last_user_id = ? AND finished_at IS NULL",
                                        batch.getLast().id(),
                                        count,
                                        batch.size() - count,
                                        id,
                                        from)
                                == 0) {
                            status.setRollbackOnly();
                            return null;
                        }
                        return count;
                    });
                    if (sent == null) {
                        log.warn("Broadcast {} is being queued by another run; this one stops", id);
                        return;
                    }
                    queued += sent;
                    lastId = batch.getLast().id();
                }
            } catch (RuntimeException ex) {
                log.error("Broadcast {} stopped after user id {}", id, lastId, ex);
                partial = true;
            }
            jdbc.update(
                    "UPDATE mail_broadcasts SET partial = ?, finished_at = now() WHERE id = ? AND finished_at IS NULL",
                    partial,
                    id);
            meterRegistry.counter("mail.broadcast.recipients").increment(queued);
        } catch (RuntimeException ex) {
            // Left unfinished: resumeStalled continues it from its saved progress.
            log.error("Broadcast {} could not be queued or marked finished; it is resumed later", id, ex);
        }
    }

    private static MailCategory category(BroadcastRequest request) {
        return MailCategory.fromKey(request.category())
                .filter(MailCategory::optional)
                .orElseThrow(() -> ApiException.badRequest(
                        ProblemType.VALIDATION_FAILED,
                        "Broadcasts go out in an optional category only, such as product-updates; "
                                + "account and security emails cannot be broadcast."));
    }

    private static String roles(BroadcastRequest request) {
        return request.roles().stream().map(Role::name).sorted().collect(Collectors.joining(","));
    }

    /** The template model: the title, and the body split into paragraphs at blank lines. */
    private static Map<String, Object> model(String title, String body) {
        List<String> paragraphs = Arrays.stream(body.split("\\R\\s*\\R"))
                .map(String::strip)
                .filter(paragraph -> !paragraph.isEmpty())
                .toList();
        return Map.of("title", title, "paragraphs", paragraphs);
    }

    private record Recipient(long id, String email, boolean enabled) {}
}
