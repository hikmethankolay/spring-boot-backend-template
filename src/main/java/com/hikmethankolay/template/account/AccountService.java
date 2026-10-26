package com.hikmethankolay.template.account;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.user.EmailVerificationRequired;
import com.hikmethankolay.template.user.RegistrationRepeated;
import com.hikmethankolay.template.user.SessionsInvalidated;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Email verification and password reset.
 *
 * <p>Tokens are 256-bit random values sent by email; only their SHA-256 hash is stored (see
 * {@link SecureTokens}). Each token is single-use, expires, and issuing a new one invalidates the
 * previous one of the same type.
 * Requests that take an email address behave the same whether or not the address exists, so they
 * cannot be used to find out which addresses have accounts.</p>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final String INVALID_TOKEN = "The link is invalid or has expired.";

    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final AccountTokenRepository tokens;

    private final UserRepository users;

    private final UserService userService;

    private final AccountMailer mailer;

    private final AppProperties.Account properties;

    private final AuditLog audit;

    /**
     * Creates the service.
     *
     * @param tokens      token persistence
     * @param users       user lookups
     * @param userService user changes
     * @param mailer      sends the emails
     * @param properties  application properties holding the token lifetimes
     * @param audit       records security events
     */
    public AccountService(
            AccountTokenRepository tokens,
            UserRepository users,
            UserService userService,
            AccountMailer mailer,
            AppProperties properties,
            AuditLog audit) {
        this.tokens = tokens;
        this.users = users;
        this.userService = userService;
        this.mailer = mailer;
        this.properties = properties.account();
        this.audit = audit;
    }

    /**
     * Revokes the user's outstanding tokens inside the transaction that changes the email, so a
     * link sent to the old address can neither reset the password nor verify the new address.
     *
     * @param event identifies the user whose address changed
     */
    @EventListener
    public void revokeTokensOnAddressChange(EmailVerificationRequired event) {
        tokens.deleteAllByUserIdAndType(event.userId(), AccountTokenType.PASSWORD_RESET);
        tokens.deleteAllByUserIdAndType(event.userId(), AccountTokenType.EMAIL_VERIFICATION);
    }

    /**
     * Revokes outstanding password reset links when the user's sessions end (e.g. a password change),
     * in the same transaction.
     *
     * @param event identifies the user
     */
    @EventListener
    public void revokeResetTokensOnSessionsEnd(SessionsInvalidated event) {
        tokens.deleteAllByUserIdAndType(event.userId(), AccountTokenType.PASSWORD_RESET);
    }

    /**
     * Sends a verification email once the transaction that registered or changed the user has committed.
     *
     * @param event identifies the user to verify
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEmailVerificationRequired(EmailVerificationRequired event) {
        users.findById(event.userId()).ifPresent(this::sendVerification);
    }

    /**
     * Tells the owner of an address that someone tried to sign up with it, once the transaction has committed.
     * The email carries a password reset link and shares the reset email's cooldown, so repeated sign-ups cannot
     * flood the inbox.
     *
     * @param event identifies the account the address belongs to
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRegistrationRepeated(RegistrationRepeated event) {
        users.findById(event.userId())
                .filter(user -> !recentlyIssued(user.getId(), AccountTokenType.PASSWORD_RESET))
                .ifPresent(user -> mailer.sendAlreadyRegistered(
                        user.getEmail(),
                        issue(user.getId(), AccountTokenType.PASSWORD_RESET, properties.passwordResetTokenTtl()),
                        properties.passwordResetTokenTtl()));
    }

    /**
     * Sends a new verification email if the address belongs to an unverified account.
     *
     * @param email the address to verify
     */
    @Transactional
    public void resendVerification(String email) {
        users.findByEmail(normalize(email))
                .filter(user -> !user.isEmailVerified())
                .filter(user -> !recentlyIssued(user.getId(), AccountTokenType.EMAIL_VERIFICATION))
                .ifPresent(this::sendVerification);
    }

    /**
     * Confirms an email address.
     *
     * @param token the token from the verification email
     * @throws ApiException 400 if the token is unknown, already used or expired
     */
    @Transactional
    public void verifyEmail(String token) {
        userService.markEmailVerified(
                consume(token, AccountTokenType.EMAIL_VERIFICATION).getUserId());
    }

    /**
     * Sends a password reset email if the address belongs to an account.
     *
     * @param email the account's address
     */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(normalize(email))
                .filter(user -> !recentlyIssued(user.getId(), AccountTokenType.PASSWORD_RESET))
                .ifPresent(user -> {
                    mailer.sendPasswordReset(
                            user.getEmail(),
                            issue(user.getId(), AccountTokenType.PASSWORD_RESET, properties.passwordResetTokenTtl()),
                            properties.passwordResetTokenTtl());
                    audit.record(AuditEvent.PASSWORD_RESET_REQUESTED, user.getId());
                });
    }

    /**
     * Sets a new password using a reset token.
     *
     * @param token       the token from the reset email
     * @param newPassword the new password
     * @throws ApiException 400 if the token is unknown, already used or expired
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        long userId = consume(token, AccountTokenType.PASSWORD_RESET).getUserId();
        userService.setPassword(userId, newPassword);
        audit.record(AuditEvent.PASSWORD_RESET_COMPLETED, userId);
    }

    /** Deletes expired tokens once an hour. Safe to run on several instances at once. */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = tokens.deleteAllExpiredBefore(Instant.now());
        if (deleted > 0) {
            log.info("Deleted {} expired account tokens", deleted);
        }
    }

    /**
     * Issues a verification token and emails it.
     *
     * @param user the user to verify
     */
    private void sendVerification(User user) {
        Duration ttl = properties.verificationTokenTtl();
        mailer.sendEmailVerification(
                user.getEmail(), issue(user.getId(), AccountTokenType.EMAIL_VERIFICATION, ttl), ttl);
    }

    /**
     * Tells whether a token was emailed to the user within the last {@link #RESEND_COOLDOWN}, so
     * anonymous requests cannot flood an inbox.
     *
     * @param userId owner of the token
     * @param type   purpose of the token
     * @return {@code true} if another email must not be sent yet
     */
    private boolean recentlyIssued(long userId, AccountTokenType type) {
        return tokens.existsByUserIdAndTypeAndCreatedAtAfter(
                userId, type, Instant.now().minus(RESEND_COOLDOWN));
    }

    /**
     * Creates a token, replacing earlier ones of the same type.
     *
     * @param userId owner of the token
     * @param type   purpose of the token
     * @param ttl    lifetime of the token
     * @return the raw token to put in the email
     */
    private String issue(long userId, AccountTokenType type, Duration ttl) {
        tokens.deleteAllByUserIdAndType(userId, type);
        String token = SecureTokens.generate();
        tokens.save(new AccountToken(
                userId, type, SecureTokens.hash(token), Instant.now().plus(ttl)));
        return token;
    }

    /**
     * Validates a token and deletes it (and any other token of the same type for that user).
     *
     * @param token the raw token
     * @param type  the required purpose
     * @return the consumed token
     * @throws ApiException 400 if the token is unknown, already used or expired
     */
    private AccountToken consume(String token, AccountTokenType type) {
        AccountToken stored = tokens.findByTokenHashAndType(SecureTokens.hash(token), type)
                .filter(candidate -> !candidate.isExpired(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest(ProblemType.INVALID_TOKEN, INVALID_TOKEN));
        tokens.deleteAllByUserIdAndType(stored.getUserId(), type);
        return stored;
    }

    /**
     * Normalises an email address the same way {@code UserService} stores it.
     *
     * @param email the address as entered
     * @return the trimmed, lower-case address
     */
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
