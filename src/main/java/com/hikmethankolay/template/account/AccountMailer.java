package com.hikmethankolay.template.account;

import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.mail.EmailOutbox;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Writes the account emails and queues them in the {@link EmailOutbox}. Links point to the frontend
 * ({@code app.mail.link-base-url}), which posts the token to the API.
 *
 * <p>Must be called inside a transaction: the email is sent only if it commits, and a mail outage
 * never breaks registration or loses the email.</p>
 */
@Component
public class AccountMailer {

    private final EmailOutbox outbox;

    private final AppProperties.Mail properties;

    /**
     * Creates the mailer.
     *
     * @param outbox     queues the messages
     * @param properties application properties holding the mail settings
     */
    public AccountMailer(EmailOutbox outbox, AppProperties properties) {
        this.outbox = outbox;
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
                "Confirm your email address",
                "Confirm your email address by opening this link:%n%n%s%n%nThe link expires in %d minutes."
                        .formatted(link("/verify-email", token), ttl.toMinutes()));
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
                "Reset your password",
                ("Someone asked to reset the password of your account. If that was you, open this link:%n%n%s%n%n"
                                + "The link expires in %d minutes. If you did not ask for this, ignore this email.")
                        .formatted(link("/reset-password", token), ttl.toMinutes()));
    }

    /**
     * Tells the owner of an address that someone tried to sign up with it, with a password reset link in case
     * it was them and they forgot that they have an account.
     *
     * @param to    recipient address
     * @param token the raw reset token
     * @param ttl   how long the link is valid
     */
    public void sendAlreadyRegistered(String to, String token, Duration ttl) {
        outbox.enqueue(
                to,
                "You already have an account",
                ("Someone tried to create an account with this email address, which already has one. If that was "
                                + "you, log in, or reset your password with this link:%n%n%s%n%n"
                                + "The link expires in %d minutes. If it was not you, ignore this email: nothing "
                                + "was changed.")
                        .formatted(link("/reset-password", token), ttl.toMinutes()));
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
