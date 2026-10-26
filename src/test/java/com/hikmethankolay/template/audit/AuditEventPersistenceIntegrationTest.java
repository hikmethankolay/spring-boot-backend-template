package com.hikmethankolay.template.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Audit events are stored in audit_events as well as logged, and the retention job removes only old ones. */
class AuditEventPersistenceIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditRetention retention;

    @Test
    void anEventIsStoredWithItsUserAndDetails() throws Exception {
        long userId = register(uniqueUsername());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT action, details->>'role' AS role FROM audit_events WHERE user_id = ? AND action = ?",
                userId,
                AuditEvent.USER_REGISTERED.action());

        assertThat(row).containsEntry("action", "user.registered").containsEntry("role", "USER");
    }

    @Test
    void retentionRemovesOnlyEventsOlderThanItsPeriod() {
        jdbc.update("INSERT INTO audit_events (action, occurred_at) VALUES ('test.old', now() - interval '400 days')");
        jdbc.update(
                "INSERT INTO audit_events (action, occurred_at) VALUES ('test.recent', now() - interval '100 days')");

        retention.purge();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'test.old'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'test.recent'", Integer.class))
                .isPositive();
    }
}
