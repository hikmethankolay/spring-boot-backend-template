package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.mail.dto.BroadcastPreview;
import com.hikmethankolay.template.mail.dto.BroadcastRequest;
import com.hikmethankolay.template.mail.dto.BroadcastResponse;
import com.hikmethankolay.template.mail.dto.BroadcastResult;
import com.hikmethankolay.template.security.LoginAttemptService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *       stops the queuing and marks the broadcast partial; the batches before it still go out. {@code finished_at}
 *       is set when queuing ends.</li>
 *   <li>Broadcast emails are sent by the outbox's scheduled runs, after the other emails waiting, so a large
 *       broadcast neither holds up a request nor delays a password reset.</li>
 *   <li>A new broadcast is refused while one is still being queued, or less than a minute after the last one,
 *       across every instance. A broadcast still unfinished after an hour is taken to have stopped with its
 *       instance and no longer blocks new ones.</li>
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
    private static final String AUDIENCE =
            " FROM users u WHERE u.role = ANY (string_to_array(?, ',')) AND (u.email_verified OR NOT ?)";

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

    private final LoginAttemptService loginAttempts;

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
     * @param batchSize          recipients queued per transaction
     * @param executor           Spring Boot's task executor, which queues broadcasts in the background and lets
     *                           them finish when the app stops ({@code spring.task.execution.shutdown})
     * @param loginAttempts      locks out repeated wrong passwords
     */
    public BroadcastService(
            JdbcTemplate jdbc,
            EmailOutbox outbox,
            MailRenderer renderer,
            UserService users,
            AuditLog audit,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            @Value("${app.mail.broadcast-batch-size}") int batchSize,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            LoginAttemptService loginAttempts) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.renderer = renderer;
        this.users = users;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = batchSize;
        this.executor = executor;
        this.loginAttempts = loginAttempts;
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
        RenderedMail sample = renderer.render(TEMPLATE, request.subject(), model(request), category, "preview");
        return new BroadcastPreview(recipients(request, category), sample.subject(), sample.text());
    }

    /**
     * Records a broadcast and starts queuing it for every user in its audience, in the background.
     *
     * @param ownerId  the owner sending it
     * @param clientIp address of the client, for the failed-password lockout
     * @param request  the broadcast, with the owner's current password
     * @return the broadcast id and the number of recipients expected
     * @throws ApiException 400 if the category is unknown or transactional or the password is wrong, 409 if a
     *                      broadcast is still being queued or started less than a minute ago, 429 if the IP or
     *                      account is locked after failed passwords, 503 if the failed-login counters cannot be
     *                      checked
     */
    public BroadcastResult send(long ownerId, String clientIp, BroadcastRequest request) {
        MailCategory category = category(request);
        // Wrong passwords count as failed logins, as when turning off two-factor authentication, so a stolen access
        // token cannot be used to guess the owner's password here.
        String accountKey = "account:" + ownerId;
        String ipKey = "ip:" + ClientIp.limitKey(clientIp);
        if (!loginAttempts.tryAcquire(ipKey, accountKey)) {
            throw ApiException.tooManyRequests(
                    ProblemType.LOGIN_LOCKED,
                    "Too many failed attempts. Please try again later.",
                    loginAttempts.lockDuration());
        }
        boolean checked = false;
        try {
            users.confirmPassword(ownerId, request.currentPassword());
            checked = true;
        } catch (ApiException ex) {
            checked = true; // a wrong password stays counted
            throw ex;
        } finally {
            // The password could not be checked (database down): no failed attempt.
            if (!checked) {
                loginAttempts.giveBack(ipKey, accountKey);
            }
        }
        loginAttempts.reset(accountKey);
        loginAttempts.release(ipKey);
        int recipients = recipients(request, category);
        long id =
                Objects.requireNonNull(newTransaction.execute(status -> start(ownerId, request, category, recipients)));
        executor.execute(() -> queue(id, request, category));
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
                    OR (finished_at IS NULL AND created_at > now() - interval '1 hour'))
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

    /** Queues every recipient in batches; runs on the task executor and always marks the broadcast finished. */
    private void queue(long id, BroadcastRequest request, MailCategory category) {
        String roles = roles(request);
        Map<String, Object> model = model(request);
        int queued = 0;
        int suppressed = 0;
        boolean partial = false;
        long lastId = 0;
        try {
            while (true) {
                List<Recipient> batch = jdbc.query(
                        "SELECT u.id, u.email" + AUDIENCE + " AND u.id > ? ORDER BY u.id LIMIT ?",
                        (row, i) -> new Recipient(row.getLong(1), row.getString(2)),
                        roles,
                        request.verifiedOnly(),
                        lastId,
                        batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                int sent = Objects.requireNonNull(newTransaction.execute(status -> (int) batch.stream()
                        .filter(recipient -> outbox.enqueueBroadcast(
                                id, recipient.id(), recipient.email(), category, TEMPLATE, request.subject(), model))
                        .count()));
                queued += sent;
                suppressed += batch.size() - sent;
                lastId = batch.getLast().id();
            }
        } catch (RuntimeException ex) {
            log.error("Broadcast {} stopped after {} recipients", id, queued + suppressed, ex);
            partial = true;
        }
        jdbc.update(
                "UPDATE mail_broadcasts SET queued = ?, suppressed = ?, partial = ?, finished_at = now() WHERE id = ?",
                queued,
                suppressed,
                partial,
                id);
        meterRegistry.counter("mail.broadcast.recipients").increment(queued);
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
    private static Map<String, Object> model(BroadcastRequest request) {
        List<String> paragraphs = Arrays.stream(request.body().split("\\R\\s*\\R"))
                .map(String::strip)
                .filter(paragraph -> !paragraph.isEmpty())
                .toList();
        return Map.of("title", request.title(), "paragraphs", paragraphs);
    }

    private record Recipient(long id, String email) {}
}
