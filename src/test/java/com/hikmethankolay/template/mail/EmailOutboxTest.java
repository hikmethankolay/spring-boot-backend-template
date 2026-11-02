package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Unit tests of the failure handling of {@link EmailOutbox#sendDue(Instant)}, with the outbox table mocked. */
class EmailOutboxTest {

    private final OutboxEmailRepository repository = mock(OutboxEmailRepository.class);

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final OutboxEmail email = new OutboxEmail("a@acme.test", "Subject", "Body");

    private final Instant start = Instant.parse("2026-11-02T12:00:00Z");

    private EmailOutbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new EmailOutbox(
                repository,
                mailSender,
                mock(PlatformTransactionManager.class),
                TestProperties.create(),
                mock(MailRenderer.class),
                mock(EmailPreferenceService.class),
                mock(UnsubscribeTokens.class),
                meterRegistry);
        // Like the real query: the email is due until it is given up.
        when(repository.lockDue(any(), anyInt()))
                .thenAnswer(call ->
                        email.getFailedAt() == null && !email.getNextAttemptAt().isAfter(call.getArgument(0))
                                ? List.of(email)
                                : List.of());
    }

    @Test
    void anyExceptionFromTheSendCountsAsAFailedAttempt() {
        doThrow(new IllegalStateException("broken transport")).when(mailSender).send(any(SimpleMailMessage.class));

        outbox.sendDue(start);

        assertThat(email.getAttempts()).isEqualTo(1);
        assertThat(email.getNextAttemptAt()).isAfter(start);
        assertThat(email.getFailedAt()).isNull();
        assertThat((String) ReflectionTestUtils.getField(email, "lastError"))
                .isEqualTo("java.lang.IllegalStateException: broken transport");
        assertThat(meterRegistry.counter("mail.outbox.failures").count()).isEqualTo(1);
        verify(repository, never()).delete(any());
    }

    @Test
    void emailIsKeptAsADeadLetterAfterTheLastAttempt() {
        doThrow(new IllegalStateException("broken transport")).when(mailSender).send(any(SimpleMailMessage.class));

        // max-attempts is 10; each run is two hours later than the last, past the backoff (at most one hour).
        for (int run = 0; run < 12; run++) {
            outbox.sendDue(start.plus(run * 2L, ChronoUnit.HOURS));
        }

        assertThat(email.getAttempts()).isEqualTo(10);
        assertThat(email.getFailedAt()).isEqualTo(start.plus(18, ChronoUnit.HOURS));
        assertThat(meterRegistry.counter("mail.outbox.dropped").count()).isEqualTo(1);
        verify(repository, never()).delete(any());
    }

    @Test
    void sentEmailIsDeleted() {
        doReturn(List.of(email), List.of()).when(repository).lockDue(any(), anyInt());

        outbox.sendDue(start);

        verify(repository).delete(email);
        assertThat(email.getAttempts()).isZero();
    }
}
