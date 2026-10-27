package com.hikmethankolay.template.account;

import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.mail.EmailOutbox;
import com.hikmethankolay.template.mail.MailRenderer;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the account emails from their templates ({@code src/main/resources/mail/}) and queues them in the
 * {@link EmailOutbox}. Links point to the frontend ({@code app.mail.link-base-url}): {@code /sign-up},
 * {@code /verify-email} and {@code /reset-password}, which post the token to the API.
 *
 * <p>Must be called inside a transaction: the email is sent only if it commits, and a mail outage
 * never breaks registration or loses the email.</p>
 */
@Component
public class AccountMailer {

    private final EmailOutbox outbox;

    private final MailRenderer renderer;

    private final AppProperties.Mail properties;

    /**
     * Creates the mailer.
     *
     * @param outbox     queues the messages
     * @param renderer   renders the email templates
     * @param properties application properties holding the mail settings
     */
    public AccountMailer(EmailOutbox outbox, MailRenderer renderer, AppProperties properties) {
        this.outbox = outbox;
        this.renderer = renderer;
        this.properties = properties.mail();
    }

    /**
     * Sends the email verification link.
     *
     * @param to    recipient address
     * @param token the raw verification token
     * @param ttl   how long the link is valid
     */
    public void sendEmailVerification(String to, String token, Duration ttl) {
        outbox.enqueue(
                to,
                renderer.render(
                        "verify-email",
                        "Confirm your email address",
                        Map.of("link", link("/verify-email", token), "ttl", humanize(ttl))));
    }

    /**
     * Sends the password reset link.
     *
     * @param to    recipient address
     * @param token the raw reset token
     * @param ttl   how long the link is valid
     */
    public void sendPasswordReset(String to, String token, Duration ttl) {
        outbox.enqueue(
                to,
                renderer.render(
                        "password-reset",
                        "Reset your password",
                        Map.of("link", link("/reset-password", token), "ttl", humanize(ttl))));
    }

    /**
     * Sends the link that finishes a sign-up, where the username and password are chosen.
     *
     * @param to    recipient address
     * @param token the sign-up token
     * @param ttl   how long the link is valid
     */
    public void sendSignUpLink(String to, String token, Duration ttl) {
        outbox.enqueue(
                to,
                renderer.render(
                        "sign-up-link",
                        "Finish creating your account",
                        Map.of("link", link("/sign-up", token), "ttl", humanize(ttl))));
    }

    /**
     * Tells the owner of an address that someone tried to sign up with it. It carries no link, so nothing has to
     * be stored for it.
     *
     * @param to recipient address
     */
    public void sendAlreadyRegistered(String to) {
        outbox.enqueue(to, renderer.render("already-registered", "You already have an account", Map.of()));
    }

    /**
     * Writes a link lifetime for people: whole hours as hours, anything else as minutes.
     *
     * @param duration the lifetime
     * @return e.g. {@code 24 hours}, {@code 1 hour} or {@code 90 minutes}
     */
    static String humanize(Duration duration) {
        long minutes = duration.toMinutes();
        return minutes % 60 == 0 ? plural(minutes / 60, "hour") : plural(minutes, "minute");
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    /**
     * Builds a frontend link carrying the token.
     *
     * @param path  frontend path
     * @param token the raw token
     * @return the absolute link
     */
    private String link(String path, String token) {
        return UriComponentsBuilder.fromUriString(properties.linkBaseUrl())
                .path(path)
                .queryParam("token", token)
                .toUriString();
    }
}
