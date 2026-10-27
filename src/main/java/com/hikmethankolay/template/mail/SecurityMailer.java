package com.hikmethankolay.template.mail;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
     * @param linkBaseUrl     the frontend's URL, for the link to the sessions page
     * @param enabled         whether alerts are sent at all
     * @param newLoginEnabled whether sign-ins from new devices are reported
     */
    public SecurityMailer(
            EmailOutbox outbox,
            MailRenderer renderer,
            MeterRegistry meterRegistry,
            @Value("${app.mail.link-base-url}") String linkBaseUrl,
            @Value("${app.mail.security-alerts.enabled}") boolean enabled,
            @Value("${app.mail.security-alerts.new-login-enabled}") boolean newLoginEnabled) {
        this.outbox = outbox;
        this.renderer = renderer;
        this.meterRegistry = meterRegistry;
        this.sessionsUrl = linkBaseUrl + "/settings/sessions";
        this.enabled = enabled;
        this.newLoginEnabled = newLoginEnabled;
    }

    /**
     * Reports a sign-in from a device the user has not used recently.
     *
     * @param to     the user's address
     * @param device the device, e.g. {@code Chrome on Windows}
     * @param ip     the client address, or {@code null} if unknown
     */
    public void newLogin(String to, String device, String ip) {
        if (newLoginEnabled) {
            send(
                    "new-login",
                    to,
                    "New sign-in to your account",
                    "Your account was signed in to from a device it has not used recently.",
                    "Device",
                    device,
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
        String name = switch (provider) {
            case "github" -> "GitHub";
            case "google" -> "Google";
            default -> provider;
        };
        send(
                "social-linked",
                to,
                "A " + name + " account was linked to your account",
                "A " + name + " account was linked to your account, and can now be used to sign in.");
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
}
