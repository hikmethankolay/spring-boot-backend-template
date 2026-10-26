package com.hikmethankolay.template.audit;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes stored audit events older than {@code app.audit.retention} (default one year) once a day, through the
 * database function {@code purge_audit_events}: the app's role cannot delete from {@code audit_events} itself, and
 * the function keeps everything younger than 90 days. Safe to run on several instances at once.
 */
@Component
public class AuditRetention {

    /** The database keeps events at least this long (purge_audit_events, V12). */
    static final Duration MINIMUM = Duration.ofDays(90);

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final JdbcTemplate jdbc;

    private final Duration retention;

    /**
     * Creates the job.
     *
     * @param jdbc      runs the purge
     * @param retention how long events are kept
     * @throws IllegalStateException if the retention is shorter than the 90 days the database keeps anyway
     */
    public AuditRetention(JdbcTemplate jdbc, @Value("${app.audit.retention:365d}") Duration retention) {
        if (retention.compareTo(MINIMUM) < 0) {
            throw new IllegalStateException("app.audit.retention must be at least 90 days, not " + retention);
        }
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /** Removes the events older than the retention. */
    @Scheduled(cron = "0 15 3 * * *")
    public void purge() {
        Integer removed = jdbc.queryForObject(
                "SELECT purge_audit_events(?)",
                Integer.class,
                Timestamp.from(Instant.now().minus(retention)));
        if (removed != null && removed > 0) {
            log.info("Removed {} audit events older than {}", removed, retention);
        }
    }
}
