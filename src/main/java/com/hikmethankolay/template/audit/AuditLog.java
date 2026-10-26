package com.hikmethankolay.template.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records security events as structured log lines on the {@code audit} logger and counts them in
 * the {@code security.audit.events} metric (tagged with {@code event}).
 *
 * <p>Each entry carries the event, the affected user, the authenticated actor and the client IP;
 * actor and IP are taken from the current request. With the {@code prod} profile the fields are
 * separate JSON attributes, so a log platform can filter on them; route the {@code audit} logger
 * to long-term storage to keep an audit trail.</p>
 *
 * <p>Each entry is also stored in the append-only {@code audit_events} table (V12), which the app's
 * database role cannot change or delete from, so the trail survives a compromised app or a lost log.
 * Inside a transaction the entry is written only after the commit, so actions that are rolled
 * back never appear in the audit trail. The row is stored in a transaction of its own; if that
 * fails, the error is logged and the request is not affected.</p>
 */
@Service
public class AuditLog {

    /** Name of the logger that receives every audit entry. */
    public static final String LOGGER_NAME = "audit";

    /** Name of the counter incremented for every entry. */
    public static final String METRIC_NAME = "security.audit.events";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private static final Logger errors = LoggerFactory.getLogger(AuditLog.class);

    private static final String INSERT = "INSERT INTO audit_events (action, user_id, actor_id, client_ip, details) "
            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb))";

    /** Fields stored in their own columns; the rest go to {@code details}. */
    private static final Set<String> COLUMNS = Set.of("event", "userId", "actorId", "clientIp");

    private final MeterRegistry meterRegistry;

    private final JdbcTemplate jdbc;

    private final TransactionTemplate ownTransaction;

    private final JsonMapper json;

    /**
     * Creates the audit log.
     *
     * @param meterRegistry registry for the event counters
     * @param jdbc          stores the events
     * @param transactions  runs each insert in a transaction of its own
     * @param json          writes the details as JSON
     */
    public AuditLog(
            MeterRegistry meterRegistry, JdbcTemplate jdbc, PlatformTransactionManager transactions, JsonMapper json) {
        this.meterRegistry = meterRegistry;
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    /**
     * Records an event.
     *
     * @param event   what happened
     * @param userId  id of the affected user, or {@code null} if unknown (e.g. a failed login)
     * @param details additional fields as alternating names and values, e.g. {@code "role", "ADMIN"};
     *                never pass passwords or tokens
     * @throws IllegalArgumentException if {@code details} has an odd number of elements
     */
    public void record(AuditEvent event, Long userId, Object... details) {
        if (details.length % 2 != 0) {
            throw new IllegalArgumentException("details must be name/value pairs");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event.action());
        if (userId != null) {
            fields.put("userId", userId);
        }
        String actor = currentActor();
        if (actor != null) {
            fields.put("actorId", actor);
        }
        String ip = currentClientIp();
        if (ip != null) {
            fields.put("clientIp", ip);
        }
        for (int i = 0; i < details.length; i += 2) {
            fields.put(String.valueOf(details[i]), details[i + 1]);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(event, fields);
                }
            });
        } else {
            write(event, fields);
        }
    }

    /**
     * Writes the log line and increments the counter.
     *
     * @param event  what happened
     * @param fields the entry's fields, starting with the event name
     */
    private void write(AuditEvent event, Map<String, Object> fields) {
        LoggingEventBuilder entry = log.atLevel(event.level());
        StringJoiner message = new StringJoiner(" ");
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            // Line breaks in a value (e.g. a login identifier) would forge extra lines in text logs.
            String text = String.valueOf(field.getValue()).replaceAll("[\\r\\n]", "_");
            entry = entry.addKeyValue(field.getKey(), field.getValue() instanceof String ? text : field.getValue());
            message.add(field.getKey() + "=" + text);
        }
        entry.log(message.toString());
        store(event, fields);
        Counter.builder(METRIC_NAME)
                .description("Security audit events")
                .tag("event", event.action())
                .register(meterRegistry)
                .increment();
    }

    /**
     * Returns the id of the authenticated caller.
     *
     * @return the authenticated principal's name (the user id for JWTs), or {@code null} if anonymous
     */
    /**
     * Stores an entry in {@code audit_events}.
     *
     * @param event  what happened
     * @param fields the entry's fields
     */
    private void store(AuditEvent event, Map<String, Object> fields) {
        Map<String, Object> details = new LinkedHashMap<>(fields);
        details.keySet().removeAll(COLUMNS);
        try {
            String detailsJson = json.writeValueAsString(details);
            ownTransaction.executeWithoutResult(status -> jdbc.update(
                    INSERT,
                    event.action(),
                    fields.get("userId"),
                    fields.get("actorId"),
                    fields.get("clientIp"),
                    detailsJson));
        } catch (RuntimeException ex) {
            // The log line above still has the event; a request must not fail because its audit row did.
            errors.error(
                    "Could not store audit event {}: {}",
                    event.action(),
                    ex.getClass().getSimpleName());
        }
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                        && authentication.isAuthenticated()
                        && !"anonymousUser".equals(authentication.getName())
                ? authentication.getName()
                : null;
    }

    /**
     * Returns the client IP of the current request.
     *
     * @return the remote address (honouring {@code server.forward-headers-strategy}), or {@code null} outside a request
     */
    private static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
