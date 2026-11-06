package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Emails users about security-relevant changes to their account, so they notice one they did not make: a sign-in
 * from a new device, and changes to the password, email address, two-factor authentication and linked accounts.
 *
 * <p>Alerts are transactional ({@link MailCategory#SECURITY}): they cannot be turned off and carry no unsubscribe
 * link. Callers queue them in the transaction of the change, so a change that rolls back sends nothing. They never
 * contain tokens, codes or new values, and an email change is reported to the old address only. All alerts share the
 * {@code security-alert} template, which ends with what to do if the change was not the user's.</p>
 *
 * <p>{@code app.mail.security-alerts.enabled} turns them all off, {@code new-login-enabled} only the new-login
 * alert. Each alert counts {@code mail.security.alerts} by name.</p>
 */
@Component
public class SecurityMailer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private final EmailOutbox outbox;

    private final MailRenderer renderer;

    private final MeterRegistry meterRegistry;

    private final String sessionsUrl;

    private final boolean enabled;

    private final boolean newLoginEnabled;

    /**
     * Creates the mailer.
     *
     * @param outbox          queues the alerts
     * @param renderer        renders the alert template
     * @param meterRegistry   registry for the alert counter
     * @param properties    application properties holding the frontend's URL (for the link to the sessions page)
     *                      and the alert switches
     */
    public SecurityMailer(
            EmailOutbox outbox, MailRenderer renderer, MeterRegistry meterRegistry, AppProperties properties) {
        this.outbox = outbox;
        this.renderer = renderer;
        this.meterRegistry = meterRegistry;
        this.sessionsUrl = properties.mail().linkBaseUrl() + "/settings/sessions";
        this.enabled = properties.mail().securityAlerts().enabled();
        this.newLoginEnabled = properties.mail().securityAlerts().newLoginEnabled();
    }

    /**
     * Reports a sign-in from a device the user has not used recently.
     *
     * @param to     the user's address
     * @param device the device, e.g. {@code Chrome on Windows}
     * @param ip     the client address, or {@code null} if unknown
     */
    public void newLogin(String to, String device, String ip) {
        // The device comes from the client's User-Agent: keep it to one short line of plain words, so it cannot
        // carry a link or instructions into a genuine security email.
        if (newLoginEnabled) {
            send(
                    "new-login",
                    to,
                    "New sign-in to your account",
                    "Your account was signed in to from a device it has not used recently.",
                    "Device",
                    plainDevice(device),
                    "IP address",
                    ip == null ? "unknown" : ip);
        }
    }

    /**
     * Reports a password change or reset.
     *
     * @param to the user's address
     */
    public void passwordChanged(String to) {
        send(
                "password-changed",
                to,
                "Your password was changed",
                "The password of your account was changed, and every device was signed out.");
    }

    /**
     * Reports an email change to the old address. The new address is not named, so a takeover cannot be completed
     * by reading this email.
     *
     * @param oldAddress the address the account had before
     */
    public void emailChanged(String oldAddress) {
        send(
                "email-changed",
                oldAddress,
                "The email address of your account was changed",
                "The email address of your account was changed from this address to another one. "
                        + "Emails about the account, including password resets, now go there.");
    }

    /**
     * Reports that two-factor authentication was turned on.
     *
     * @param to the user's address
     */
    public void twoFactorEnabled(String to) {
        send(
                "mfa-enabled",
                to,
                "Two-factor authentication was turned on",
                "Two-factor authentication was turned on for your account: signing in now also needs a code.");
    }

    /**
     * Reports that two-factor authentication was turned off.
     *
     * @param to the user's address
     */
    public void twoFactorDisabled(String to) {
        send(
                "mfa-disabled",
                to,
                "Two-factor authentication was turned off",
                "Two-factor authentication was turned off for your account: signing in now needs only the password.");
    }

    /**
     * Reports that an external account was linked, which can now sign in to this one.
     *
     * @param to       the user's address
     * @param provider the provider id, e.g. {@code github}
     */
    public void socialAccountLinked(String to, String provider) {
        String name = providerName(provider);
        send(
                "social-linked",
                to,
                "A " + name + " account was linked to your account",
                "A " + name + " account was linked to your account, and can now be used to sign in.");
    }

    /**
     * Reports that an external account was unlinked and can no longer sign in to this one.
     *
     * @param to       the user's address
     * @param provider the provider id, e.g. {@code github}
     */
    public void socialAccountUnlinked(String to, String provider) {
        String name = providerName(provider);
        send(
                "social-unlinked",
                to,
                "A " + name + " account was unlinked from your account",
                "A " + name + " account was unlinked from your account, and can no longer be used to sign in.");
    }

    /**
     * Reports that the two-factor recovery codes were replaced.
     *
     * @param to the user's address
     */
    public void recoveryCodesRegenerated(String to) {
        send(
                "recovery-codes-regenerated",
                to,
                "New two-factor recovery codes were created",
                "New recovery codes were created for your account, and the old ones no longer work.");
    }

    /**
     * Reports that a personal access token was created, which can call the API as this user until it expires.
     *
     * @param to        the user's address
     * @param name      the label the user gave it
     * @param scope     {@code READ} or {@code WRITE}
     * @param expiresAt when it stops working
     */
    public void accessTokenCreated(String to, String name, String scope, Instant expiresAt) {
        send(
                "access-token-created",
                to,
                "A new access token was created for your account",
                "A personal access token was created for your account. It can call the API as you until it expires "
                        + "or you revoke it.",
                "Name",
                name,
                "Scope",
                scope,
                "Expires",
                WHEN.format(expiresAt));
    }

    /**
     * Reports that a webhook endpoint was turned off because deliveries kept failing or the receiver asked to stop.
     *
     * @param to     the owner's address
     * @param url    the endpoint URL
     * @param reason why it was turned off
     */
    public void webhookDisabled(String to, String url, String reason) {
        send(
                "webhook-disabled",
                to,
                "A webhook endpoint was turned off",
                "One of your webhook endpoints was turned off and no longer receives events. Fix the receiver, then turn "
                        + "the endpoint back on.",
                "Endpoint",
                url,
                "Reason",
                reason);
    }

    private static String providerName(String provider) {
        return switch (provider) {
            case "github" -> "GitHub";
            case "google" -> "Google";
            default -> provider;
        };
    }

    private void send(String alert, String to, String subject, String message, String... details) {
        if (!enabled) {
            return;
        }
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < details.length; i += 2) {
            facts.put(details[i], details[i + 1]);
        }
        facts.put("Time", WHEN.format(Instant.now()));
        outbox.enqueue(
                to,
                renderer.render(
                        "security-alert",
                        subject,
                        Map.of("message", message, "details", facts, "sessionsUrl", sessionsUrl)));
        meterRegistry.counter("mail.security.alerts", "alert", alert).increment();
    }

    /**
     * Reduces a User-Agent derived device name to letters, digits, spaces and {@code ./()-}, without links, at most
     * 60 characters.
     *
     * @param device the device name, or {@code null}
     * @return the plain device name, or {@code unknown}
     */
    static String plainDevice(String device) {
        if (device == null) {
            return "unknown";
        }
        String plain = device.replaceAll("(?i)(https?:|www\\.)\\S*", " ")
                .replaceAll("[^\\p{L}\\p{N} ./()-]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (plain.isEmpty()) {
            return "unknown";
        }
        return plain.length() <= 60 ? plain : plain.substring(0, 60);
    }
}
