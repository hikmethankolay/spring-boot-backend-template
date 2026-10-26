package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The connection pool must apply the server-side timeouts from application.yml to every connection. */
class DatabaseSettingsIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void connectionsHaveStatementAndIdleTransactionTimeouts() {
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("30s");
        assertThat(jdbc.queryForObject("show idle_in_transaction_session_timeout", String.class))
                .isEqualTo("1min");
    }

    /** A role other than the owner (the app's, in production) may add audit events but never change or remove them. */
    @Test
    void auditEventsAreAppendOnlyForTheAppRole() {
        assertThat(asAppRole("INSERT INTO audit_events (action) VALUES ('test.event')"))
                .isNull();
        assertThat(asAppRole("UPDATE audit_events SET action = 'forged'")).isEqualTo("42501");
        assertThat(asAppRole("DELETE FROM audit_events")).isEqualTo("42501");
        assertThat(asAppRole("TRUNCATE audit_events")).isEqualTo("42501");
    }

    /** Retention runs through purge_audit_events, which works for the app role but keeps the last 90 days. */
    @Test
    void thePurgeFunctionKeepsTheLast90Days() {
        assertThat(asAppRole("SELECT purge_audit_events(now() - interval '400 days')"))
                .isNull();
        assertThat(asAppRole("SELECT purge_audit_events(now() - interval '10 days')"))
                .isNotNull();
    }

    /**
     * Runs a statement as a role that is not the table owner, granted every privilege on audit_events, in a
     * transaction that is always rolled back.
     *
     * @return {@code null} if the statement succeeded, else its SQLSTATE
     */
    private String asAppRole(String sql) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        return transaction.execute(status -> {
            status.setRollbackOnly();
            jdbc.execute("CREATE ROLE audit_app_test NOLOGIN");
            jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON audit_events TO audit_app_test");
            jdbc.execute("SET LOCAL ROLE audit_app_test");
            try {
                jdbc.execute(sql);
                return null;
            } catch (DataAccessException ex) {
                return ex.getMostSpecificCause() instanceof SQLException sqlException
                        ? sqlException.getSQLState()
                        : "unknown";
            }
        });
    }
}
