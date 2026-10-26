package com.hikmethankolay.template.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class AuditLogTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditLog audit = new AuditLog(
            registry,
            jdbc,
            mock(PlatformTransactionManager.class),
            JsonMapper.builder().build());

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double count(AuditEvent event) {
        var counter =
                registry.find(AuditLog.METRIC_NAME).tag("event", event.action()).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void writesEventWithActorIpAndDetails(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("1", null, "ROLE_ADMIN"));

        audit.record(AuditEvent.ROLE_CHANGED, 42L, "from", "USER", "to", "ADMIN");

        assertThat(output)
                .contains("event=admin.role_changed userId=42 actorId=1 clientIp=203.0.113.7 from=USER to=ADMIN");
        assertThat(count(AuditEvent.ROLE_CHANGED)).isEqualTo(1);
    }

    @Test
    void lineBreaksInValuesCannotForgeEntries(CapturedOutput output) {
        audit.record(AuditEvent.LOGIN_FAILED, null, "identifierHash", "abc\nevent=admin.role_changed");

        assertThat(output).contains("identifierHash=abc_event=admin.role_changed");
        assertThat(output).doesNotContain("\nevent=admin.role_changed");
    }

    @Test
    void waitsForTheTransactionToCommit(CapturedOutput output) {
        TransactionSynchronizationManager.initSynchronization();

        audit.record(AuditEvent.PASSWORD_CHANGED, 7L);
        assertThat(output).doesNotContain("user.password_changed");
        assertThat(count(AuditEvent.PASSWORD_CHANGED)).isZero();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(output).contains("event=user.password_changed userId=7");
        assertThat(count(AuditEvent.PASSWORD_CHANGED)).isEqualTo(1);
    }

    @Test
    void rejectsUnpairedDetails() {
        assertThatThrownBy(() -> audit.record(AuditEvent.LOGOUT, 1L, "orphan"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventsAreStoredWithTheirDetailsAsJson() {
        audit.record(AuditEvent.ROLE_CHANGED, 7L, "from", "USER", "to", "ADMIN");

        verify(jdbc)
                .update(
                        anyString(),
                        eq("admin.role_changed"),
                        eq(7L),
                        eq(null),
                        eq(null),
                        eq("{\"from\":\"USER\",\"to\":\"ADMIN\"}"));
    }

    @Test
    void aFailedInsertIsLoggedAndDoesNotFailTheCaller(CapturedOutput output) {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        audit.record(AuditEvent.ROLE_CHANGED, 7L);

        assertThat(output).contains("Could not store audit event admin.role_changed");
    }
}
