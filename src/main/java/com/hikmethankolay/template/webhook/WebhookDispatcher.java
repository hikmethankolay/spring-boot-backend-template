package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends due webhook deliveries and records the outcome. Runs on every instance; row locks keep them apart.
 *
 * <p>Each delivery is handled in three steps: a short transaction locks it and leases it (moves its due time past the
 * attempt), the HTTP call runs with no transaction or connection held, and a second transaction records the result.
 * A sender that dies mid-call leaves the lease to expire, and the delivery is sent again: delivery is at least once,
 * and receivers drop duplicates by {@code webhook-id}.</p>
 *
 * <p>A 2xx answer is a success. Anything else is retried after 30s, 2m, 10m, 1h, 6h and then daily, up to
 * {@code app.webhooks.max-attempts}. A {@code 410 Gone}, or {@code app.webhooks.disable-after-failures} failed
 * attempts in a row, turns the endpoint off, gives up its pending deliveries and emails its owner.</p>
 */
@Component
public class WebhookDispatcher {

    private static final int BATCH_SIZE = 50;

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofHours(6),
            Duration.ofDays(1));

    /** Shown to the owner when the endpoint is turned off because its secret no longer decrypts. */
    static final String UNDECRYPTABLE = "Its signing secret cannot be decrypted (was its key removed from "
            + "WEBHOOK_ENCRYPTION_KEY?). Rotate the secret, update the receiver, then turn the endpoint back on.";

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookDeliveryRepository deliveries;

    private final WebhookEndpointRepository endpoints;

    private final UserRepository users;

    private final WebhookSecrets webhookSecrets;

    private final WebhookSender sender;

    private final WebhookProperties properties;

    private final SecurityMailer mailer;

    private final AuditLog audit;

    private final MeterRegistry meterRegistry;

    private final TransactionTemplate transaction;

    private final Duration lease;

    /**
     * Creates the dispatcher.
     *
     * @param deliveries    the outbox
     * @param endpoints     the receivers
     * @param users         the endpoint owners
     * @param webhookSecrets decrypts the signing secrets
     * @param sender        posts the messages
     * @param properties    attempts, timeouts and thresholds
     * @param mailer        tells an owner their endpoint was turned off
     * @param audit         records that
     * @param meterRegistry counts outcomes in {@code webhook.deliveries}
     * @param transactions  runs each step in its own transaction
     */
    public WebhookDispatcher(
            WebhookDeliveryRepository deliveries,
            WebhookEndpointRepository endpoints,
            UserRepository users,
            WebhookSecrets webhookSecrets,
            WebhookSender sender,
            WebhookProperties properties,
            SecurityMailer mailer,
            AuditLog audit,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactions) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.users = users;
        this.webhookSecrets = webhookSecrets;
        this.sender = sender;
        this.properties = properties;
        this.mailer = mailer;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lease =
                properties.connectTimeout().plus(properties.responseTimeout()).plusMinutes(1);
    }

    /** Sends the deliveries that are due. */
    @Scheduled(fixedDelayString = "${app.webhooks.poll-interval}")
    public void deliverDue() {
        if (properties.enabled()) {
            deliverDue(Instant.now());
        }
    }

    /**
     * Sends the deliveries due at a given time.
     *
     * @param now the time to compare due dates with; tests pass a later time to skip the backoff
     * @return how many deliveries were attempted or given up
     */
    public int deliverDue(Instant now) {
        int handled = 0;
        while (handled < BATCH_SIZE) {
            Optional<Attempt> attempt = transaction.execute(status -> claim(now));
            if (attempt == null || attempt.isEmpty()) {
                break;
            }
            handled++;
            Attempt claimed = attempt.get();
            if (claimed.url() == null) {
                continue;
            }
            WebhookSender.Result result = sender.send(
                    claimed.url(), claimed.messageId(), claimed.timestamp(), claimed.signature(), claimed.payload());
            transaction.executeWithoutResult(status -> record(claimed.deliveryId(), result, now));
        }
        return handled;
    }

    /** Deletes sent and failed deliveries older than {@code app.webhooks.delivery-retention}, once an hour. */
    @Scheduled(cron = "0 55 * * * *")
    @Transactional
    public void deleteOldDeliveries() {
        int deleted = deliveries.deleteFinishedBefore(Instant.now().minus(properties.deliveryRetention()));
        if (deleted > 0) {
            log.info("Deleted {} finished webhook deliveries", deleted);
        }
    }

    private Optional<Attempt> claim(Instant now) {
        Optional<WebhookDelivery> due = deliveries.lockDue(now);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        WebhookDelivery delivery = due.get();
        WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElse(null);
        User owner =
                endpoint == null ? null : users.findById(endpoint.getUserId()).orElse(null);
        if (endpoint == null || !endpoint.isEnabled() || owner == null || owner.getSuspendedAt() != null) {
            delivery.markFailed(null, "The endpoint is turned off or its owner suspended", now);
            count("skipped");
            return Optional.of(Attempt.skipped(delivery.getId()));
        }
        Optional<String> current = webhookSecrets.decrypt(endpoint.getSecret(), endpoint.getUserId());
        if (current.isEmpty()) {
            // Throwing here would roll back and pick the same row on every poll, blocking the queue behind it.
            delivery.markFailed(null, "The signing secret cannot be decrypted", now);
            disable(endpoint, UNDECRYPTABLE, now);
            count("failed");
            return Optional.of(Attempt.skipped(delivery.getId()));
        }
        delivery.lease(now.plus(lease));
        long timestamp = Instant.now().getEpochSecond();
        List<String> secrets = new ArrayList<>();
        secrets.add(current.get());
        String previous = endpoint.getPreviousSecret(now);
        if (previous != null) {
            // A previous secret whose key is gone just stops signing; the current one still does.
            webhookSecrets.decrypt(previous, endpoint.getUserId()).ifPresent(secrets::add);
        }
        String signature =
                WebhookSigner.signatureHeader(secrets, delivery.getMessageId(), timestamp, delivery.getPayload());
        return Optional.of(new Attempt(
                delivery.getId(),
                endpoint.getUrl(),
                delivery.getMessageId(),
                timestamp,
                signature,
                delivery.getPayload()));
    }

    private void record(long deliveryId, WebhookSender.Result result, Instant now) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        WebhookEndpoint endpoint = delivery == null
                ? null
                : endpoints.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null) {
            return; // Deleted while the request ran.
        }
        if (result.succeeded()) {
            delivery.markSent(result.status(), now);
            endpoint.recordSuccess();
            count("sent");
            return;
        }
        String error = result.error() != null ? result.error() : "HTTP " + result.status();
        int failures = endpoint.recordFailure();
        String disableReason = null;
        if (Integer.valueOf(410).equals(result.status())) {
            disableReason = "The receiver answered 410 Gone.";
        } else if (failures >= properties.disableAfterFailures()) {
            disableReason = failures + " failed attempts in a row.";
        }
        if (disableReason != null || delivery.getAttempts() + 1 >= properties.maxAttempts()) {
            delivery.markFailed(result.status(), error, now);
            count("failed");
        } else {
            delivery.markRetry(result.status(), error, now.plus(backoff(delivery.getAttempts())));
            count("retry");
        }
        if (disableReason != null && endpoint.isEnabled()) {
            disable(endpoint, disableReason, now);
        }
    }

    private void disable(WebhookEndpoint endpoint, String reason, Instant now) {
        endpoint.disable(reason);
        deliveries.failPending(endpoint.getId(), now);
        audit.record(
                AuditEvent.WEBHOOK_DISABLED, endpoint.getUserId(), "endpointId", endpoint.getId(), "reason", reason);
        users.findById(endpoint.getUserId())
                .ifPresent(user -> mailer.webhookDisabled(user.getEmail(), endpoint.getUrl(), reason));
        // The reason is in the audit event and the email; the log line carries only the id.
        log.warn("Turned off webhook endpoint {}", endpoint.getId());
    }

    private void count(String outcome) {
        meterRegistry.counter("webhook.deliveries", "outcome", outcome).increment();
    }

    /**
     * Returns the wait before the next attempt.
     *
     * @param attempts attempts made before this failure
     * @return 30s, 2m, 10m, 1h, 6h, then a day
     */
    static Duration backoff(int attempts) {
        return BACKOFF.get(Math.min(attempts, BACKOFF.size() - 1));
    }

    /**
     * A claimed delivery, ready to send.
     *
     * @param deliveryId the delivery
     * @param url        the endpoint URL, or {@code null} if the delivery was given up instead
     * @param messageId  the {@code webhook-id}
     * @param timestamp  the {@code webhook-timestamp}
     * @param signature  the {@code webhook-signature}
     * @param payload    the body
     */
    private record Attempt(
            long deliveryId, String url, String messageId, long timestamp, String signature, String payload) {

        static Attempt skipped(long deliveryId) {
            return new Attempt(deliveryId, null, null, 0, null, null);
        }
    }
}
