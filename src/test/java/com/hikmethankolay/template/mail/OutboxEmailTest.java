package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests of {@link OutboxEmail#recordFailure}. */
class OutboxEmailTest {

    private final Instant next = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void failureIsCountedAndRescheduled() {
        OutboxEmail email = new OutboxEmail("a@acme.test", "Subject", "Body");

        email.recordFailure(null, next);
        email.recordFailure("refused", next);

        assertThat(email.getAttempts()).isEqualTo(2);
        assertThat(email.getNextAttemptAt()).isEqualTo(next);
        assertThat(lastError(email)).isEqualTo("refused");
    }

    @Test
    void missingErrorStaysMissing() {
        OutboxEmail email = new OutboxEmail("a@acme.test", "Subject", "Body");

        email.recordFailure(null, next);

        assertThat(lastError(email)).isNull();
    }

    @Test
    void longErrorIsCutTo500Characters() {
        OutboxEmail email = new OutboxEmail("a@acme.test", "Subject", "Body");

        email.recordFailure("x".repeat(500), next);
        assertThat(lastError(email)).hasSize(500);

        email.recordFailure("y".repeat(501), next);
        assertThat(lastError(email)).isEqualTo("y".repeat(500));
    }

    private static String lastError(OutboxEmail email) {
        return (String) ReflectionTestUtils.getField(email, "lastError");
    }
}
