package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class EmailOutboxIntegrationTest extends IntegrationTest {

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private OutboxEmailRepository repository;

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private TransactionTemplate transaction;

    @AfterEach
    void stopOutage() {
        mail.failNext(0);
    }

    private void enqueue(String to) {
        transaction.executeWithoutResult(status -> outbox.enqueue(to, "Outbox test", "Hello"));
    }

    private boolean queued(String to) {
        return repository.findAll().stream()
                .anyMatch(email -> email.getRecipient().equals(to));
    }

    @Test
    void emailIsSentRightAfterCommitAndRemovedFromTheOutbox() {
        enqueue("outbox-ok@example.com");

        assertThat(mail.latest("outbox-ok@example.com", "Outbox test")).isPresent();
        assertThat(queued("outbox-ok@example.com")).isFalse();
    }

    @Test
    void onlyOneEmailIsSentImmediatelyAtATime() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mail.holdNextSend(entered, release);
        Thread slow = Thread.ofVirtual().start(() -> enqueue("outbox-slow@example.com"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // The slow send holds the slot, so this one stays queued for the scheduler.
        enqueue("outbox-waiting@example.com");
        assertThat(queued("outbox-waiting@example.com")).isTrue();
        assertThat(mail.latest("outbox-waiting@example.com", "Outbox test")).isEmpty();

        release.countDown();
        slow.join();
        assertThat(mail.latest("outbox-slow@example.com", "Outbox test")).isPresent();
        outbox.sendDue(Instant.now());
        assertThat(mail.latest("outbox-waiting@example.com", "Outbox test")).isPresent();
    }

    /**
     * Each due email is sent in its own transaction. In one batch transaction the connection idles while SMTP works,
     * PostgreSQL ends it after idle_in_transaction_session_timeout, and the emails already sent are sent again.
     */
    @Test
    void eachDueEmailIsCommittedBeforeTheNextIsSent() throws Exception {
        mail.failNext(2);
        enqueue("outbox-first@example.com");
        enqueue("outbox-second@example.com");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mail.holdNextSendTo("outbox-second@example.com", entered, release);

        Thread poll =
                Thread.ofVirtual().start(() -> outbox.sendDue(Instant.now().plus(1, ChronoUnit.HOURS)));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mail.latest("outbox-first@example.com", "Outbox test")).isPresent();
            assertThat(queued("outbox-first@example.com")).isFalse();
        } finally {
            release.countDown();
            poll.join();
        }
        assertThat(mail.latest("outbox-second@example.com", "Outbox test")).isPresent();
        assertThat(queued("outbox-second@example.com")).isFalse();
    }

    @Test
    void rolledBackChangeSendsNothing() {
        transaction.executeWithoutResult(status -> {
            outbox.enqueue("outbox-rollback@example.com", "Outbox test", "Hello");
            status.setRollbackOnly();
        });

        assertThat(mail.latest("outbox-rollback@example.com", "Outbox test")).isEmpty();
        assertThat(queued("outbox-rollback@example.com")).isFalse();
    }

    @Test
    void failedEmailIsRetriedWithBackoff() {
        mail.failNext(1);
        enqueue("outbox-retry@example.com");

        assertThat(mail.latest("outbox-retry@example.com", "Outbox test")).isEmpty();
        OutboxEmail pending = repository.findAll().stream()
                .filter(email -> email.getRecipient().equals("outbox-retry@example.com"))
                .findFirst()
                .orElseThrow();
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getNextAttemptAt()).isAfter(Instant.now());

        outbox.sendDue(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(mail.latest("outbox-retry@example.com", "Outbox test")).isPresent();
        assertThat(queued("outbox-retry@example.com")).isFalse();
    }

    @Test
    void emailIsDroppedAfterTheLastAttempt() {
        mail.failNext(100);
        enqueue("outbox-drop@example.com");

        // max-attempts is 10: the first attempt plus nine retries.
        for (int retry = 1; retry < 10; retry++) {
            outbox.sendDue(Instant.now().plus(retry * 2L, ChronoUnit.HOURS));
        }

        assertThat(queued("outbox-drop@example.com")).isFalse();
        assertThat(mail.latest("outbox-drop@example.com", "Outbox test")).isEmpty();
    }
}
