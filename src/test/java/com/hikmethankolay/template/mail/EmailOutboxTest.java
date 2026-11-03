package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Unit tests of the failure handling of {@link EmailOutbox#sendDue(Instant)}, with the outbox table mocked. */
@ExtendWith(OutputCaptureExtension.class)
class EmailOutboxTest {

    private final OutboxEmailRepository repository = mock(OutboxEmailRepository.class);

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final OutboxEmail email = new OutboxEmail("a@acme.test", "Subject", "Body");

    // The email is due from its creation (the real clock), so a fixed instant would stop being due once it passed.
    private final Instant start = email.getNextAttemptAt();

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

    /** Before the fix, the SMTP error text, which can name recipients, was logged. */
    @Test
    void failureLogsTheErrorTypeNotItsText(CapturedOutput output) {
        doThrow(new MailSendException("550 rejected secret@acme.test"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        outbox.sendDue(start);

        assertThat(output).contains("MailSendException").doesNotContain("secret@acme.test");
        assertThat((String) ReflectionTestUtils.getField(email, "lastError")).contains("secret@acme.test");
    }

    /** Before the fix, one run stopped after 50 emails, however many were due. */
    @Test
    void oneRunSendsEveryEmailDue() {
        int[] left = {120};
        doAnswer(call -> left[0]-- > 0 ? List.of(new OutboxEmail("a@acme.test", "S", "B")) : List.of())
                .when(repository)
                .lockDue(any(), anyInt());

        outbox.sendDue(start);

        verify(mailSender, times(120)).send(any(SimpleMailMessage.class));
    }

    @Test
    void queueGaugesCountOncePerScrapeWindow() {
        when(repository.countByBroadcastIdIsNullAndFailedAtIsNull()).thenReturn(3L);

        assertThat(meterRegistry.get("mail.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(meterRegistry.get("mail.outbox.pending").gauge().value()).isEqualTo(3);
        meterRegistry.get("mail.outbox.broadcast.pending").gauge().value();

        verify(repository, times(1)).countByBroadcastIdIsNullAndFailedAtIsNull();
        verify(repository, times(1)).countByBroadcastIdIsNotNullAndFailedAtIsNull();
    }

    @Test
    void sentEmailIsDeleted() {
        doReturn(List.of(email), List.of()).when(repository).lockDue(any(), anyInt());

        outbox.sendDue(start);

        verify(repository).delete(email);
        assertThat(email.getAttempts()).isZero();
    }
}
