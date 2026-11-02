package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.user.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of outbound webhooks ({@code app.webhooks}), so each deployment decides who may receive what and where.
 *
 * @param enabled               {@code false} stops queuing and sending, and refuses the webhook endpoints
 * @param encryptionKey         keys that encrypt the endpoints' signing secrets in the database, comma-separated like
 *                              {@code JWT_SECRET}: the first encrypts, every key decrypts. Separate from
 *                              {@code MFA_ENCRYPTION_KEY}, so each can be rotated and protected on its own. Required while
 *                              webhooks are enabled
 * @param adminEventsMinRole    the role an endpoint's owner needs to receive application-wide events such as
 *                              {@code user.registered}; {@code OWNER} keeps them to the owner
 * @param requireHttps          refuse {@code http://} endpoint URLs ({@code true} in the {@code prod} profile)
 * @param allowPrivateAddresses deliver to loopback, private and link-local addresses; only for local development and
 *                              tests, never in production (it lets users reach internal services)
 * @param maxEndpointsPerUser   how many endpoints a user may register
 * @param connectTimeout        time allowed to connect to a receiver
 * @param responseTimeout       time allowed for a receiver to answer
 * @param maxAttempts           attempts per delivery before it is marked failed (backoff 30s, 2m, 10m, 1h, 6h, then
 *                              daily)
 * @param disableAfterFailures  consecutive failed attempts after which an endpoint is turned off and its owner emailed
 * @param pollInterval          how often each instance looks for due deliveries
 * @param deliveryRetention     how long sent and failed deliveries are kept for the delivery log
 */
@Validated
@ConfigurationProperties(prefix = "app.webhooks")
public record WebhookProperties(
        boolean enabled,
        List<String> encryptionKey,
        @NotNull Role adminEventsMinRole,
        boolean requireHttps,
        boolean allowPrivateAddresses,
        @Positive int maxEndpointsPerUser,
        @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @NotNull @DurationMin(millis = 1) Duration responseTimeout,
        @Positive int maxAttempts,
        @Positive int disableAfterFailures,
        @NotNull @DurationMin(millis = 1) Duration pollInterval,
        @NotNull @DurationMin(millis = 1) Duration deliveryRetention) {

    /** Creates the settings, treating a missing key setting as an empty list. */
    public WebhookProperties {
        encryptionKey = encryptionKey == null ? List.of() : List.copyOf(encryptionKey);
    }

    /**
     * Requires a key while webhooks are enabled.
     *
     * @return {@code false} if webhooks are enabled without a key
     */
    @AssertTrue(message = "must be set while webhooks are enabled (WEBHOOK_ENCRYPTION_KEY; scripts/setup.sh adds it)")
    public boolean isEncryptionKeySet() {
        return !enabled || !encryptionKey.isEmpty();
    }

    /**
     * Rejects short or placeholder keys, like {@code MFA_ENCRYPTION_KEY}.
     *
     * @return {@code false} if a key has fewer than 32 characters or fewer than 10 distinct ones
     */
    @AssertTrue(
            message =
                    "must be at least 32 random characters, not repeated or placeholder text (WEBHOOK_ENCRYPTION_KEY)")
    public boolean isEncryptionKeyStrong() {
        return encryptionKey.stream()
                .allMatch(key -> key.length() >= 32 && key.chars().distinct().count() >= 10);
    }
}
