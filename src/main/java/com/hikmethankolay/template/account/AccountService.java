package com.hikmethankolay.template.account;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.security.SignUpTokens;
import com.hikmethankolay.template.user.EmailChangeRequested;
import com.hikmethankolay.template.user.EmailVerificationRequired;
import com.hikmethankolay.template.user.SessionsInvalidated;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import com.hikmethankolay.template.user.dto.UserResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Self-service sign-up, email verification and password reset.
 *
 * <p>Sign-up is email-first: {@link #signUp} only takes an address and emails a link; the username and password
 * are chosen when the link is opened ({@link #completeSignUp}). An address that already has an account gets a
 * notice instead. Nothing is created or stored before the link is opened, so sign-ups reveal nothing about which
 * addresses are registered, and nobody can prepare an account for someone else's address.</p>
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

    private final SignUpTokens signUpTokens;

    private final UserRepository users;

    private final UserService userService;

    private final AccountMailer mailer;

    private final AppProperties.Account properties;

    private final AuditLog audit;

    private final StringRedisTemplate redis;

    private final String signUpCooldownPrefix;

    /**
     * Creates the service.
     *
     * @param tokens      token persistence
     * @param signUpTokens issues and checks the tokens in sign-up links
     * @param users       user lookups
     * @param userService user changes
     * @param mailer      sends the emails
     * @param properties  application properties holding the token lifetimes
     * @param audit       records security events
     * @param redis       throttles sign-up emails per address
     * @param applicationName prefix of the Redis keys
     */
    public AccountService(
            AccountTokenRepository tokens,
            SignUpTokens signUpTokens,
            UserRepository users,
            UserService userService,
            AccountMailer mailer,
            AppProperties properties,
            AuditLog audit,
            StringRedisTemplate redis,
            @Value("${spring.application.name}") String applicationName) {
        this.tokens = tokens;
        this.signUpTokens = signUpTokens;
        this.users = users;
        this.userService = userService;
        this.mailer = mailer;
        this.properties = properties.account();
        this.audit = audit;
        this.redis = redis;
        this.signUpCooldownPrefix = applicationName + ":sign-up-cooldown:";
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
     * Revokes outstanding password reset links and cancels a pending email change when the user's sessions end (a
     * password change or reset, a role change, a suspension), in the same transaction. Ending the sessions is how an
     * account is taken back, so whoever asked to move it to their own address must not finish that afterwards.
     *
     * @param event identifies the user
     */
    @EventListener
    public void revokeLinksOnSessionsEnd(SessionsInvalidated event) {
        tokens.deleteAllByUserIdAndType(event.userId(), AccountTokenType.PASSWORD_RESET);
        tokens.deleteAllByUserIdAndType(event.userId(), AccountTokenType.EMAIL_CHANGE);
        users.findById(event.userId()).ifPresent(user -> user.setPendingEmail(null));
    }

    /**
     * Revokes the link of an earlier email change in the transaction that asks for a new one, so it can never confirm
     * the new address; the new link is sent after the commit.
     *
     * @param event identifies the user changing their address
     */
    @EventListener
    public void revokeEarlierEmailChangeLinks(EmailChangeRequested event) {
        tokens.deleteAllByUserIdAndType(event.userId(), AccountTokenType.EMAIL_CHANGE);
    }

    /**
     * Sends a verification email once the transaction that registered or changed the user has committed.
     *
     * @param event identifies the user to verify
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEmailVerificationRequired(EmailVerificationRequired event) {
        users.findById(event.userId()).ifPresent(user -> sendVerification(user, false));
    }

    /**
     * Starts a sign-up: emails the address a link to finish it, or, if the address already has an account, a
     * notice saying so. Either way the answer is the same and nothing is stored in the database. At most one such
     * email goes to an address per {@link #RESEND_COOLDOWN}, checked before the address is looked up so both
     * cases are throttled alike; if Redis is unavailable the email is sent.
     *
     * @param email the email address
     */
    @Transactional
    public void signUp(String email) {
        String address = normalize(email);
        if (!firstSignUpEmailInCooldown(address)) {
            return;
        }
        Optional<User> existing = users.findByEmail(address);
        if (existing.isPresent()) {
            audit.record(AuditEvent.REGISTRATION_REPEATED, existing.get().getId());
            mailer.sendAlreadyRegistered(address);
        } else {
            mailer.sendSignUpLink(address, signUpTokens.issue(address), properties.verificationTokenTtl());
        }
    }

    /**
     * Finishes a sign-up: creates the account, already verified, for the address the link was sent to.
     *
     * @param token       the token from the sign-up link
     * @param username    the chosen username
     * @param rawPassword the chosen password
     * @return the created user
     * @throws ApiException 400 if the token is forged or expired or the password is too short or breached, 409 if
     *                      the username is taken or the address already has an account (e.g. the link was used)
     */
    @Transactional
    public UserResponse completeSignUp(String token, String username, String rawPassword) {
        String address = signUpTokens
                .verify(token)
                .orElseThrow(() -> ApiException.badRequest(ProblemType.INVALID_TOKEN, INVALID_TOKEN));
        UserResponse user =
                userService.registerConfirmed(username, address, userService.encodeNewPassword(false, rawPassword));
        signUpTokens.markUsed(token);
        return user;
    }

    /**
     * Claims the sign-up email of an address for the next {@link #RESEND_COOLDOWN}. Only a hash of the address
     * goes to Redis.
     *
     * @param address the normalised address
     * @return {@code true} if no sign-up email went to the address within the cooldown
     */
    private boolean firstSignUpEmailInCooldown(String address) {
        String key = signUpCooldownPrefix + SecureTokens.hash(address);
        try {
            boolean claimed = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", RESEND_COOLDOWN));
            if (claimed && TransactionSynchronizationManager.isSynchronizationActive()) {
                // A rolled-back sign-up queued no email, so a retry within the cooldown must not be swallowed.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            releaseSignUpCooldown(key);
                        }
                    }
                });
            }
            return claimed;
        } catch (DataAccessException ex) {
            log.warn(
                    "Sign-up email cooldown unavailable, sending anyway: {}",
                    ex.getClass().getSimpleName());
            return true;
        }
    }

    private void releaseSignUpCooldown(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException ex) {
            log.warn("Could not release the sign-up email cooldown: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Sends a new link if the address belongs to an unverified account, or is the new address an email change is
     * waiting for (the link sent to it was lost or expired).
     *
     * @param email the address to verify
     */
    @Transactional
    public void resendVerification(String email) {
        String address = normalize(email);
        users.findByEmail(address)
                .filter(user -> !user.isEmailVerified())
                .filter(user -> !recentlyIssued(user.getId(), AccountTokenType.EMAIL_VERIFICATION))
                .ifPresent(user -> sendVerification(user, true));
        users.findAllByPendingEmail(address).stream()
                .filter(user -> !recentlyIssued(user.getId(), AccountTokenType.EMAIL_CHANGE))
                .forEach(user -> sendEmailChangeLink(user, true));
    }

    /**
     * Confirms an email address with the link sent to it: the address of a new or admin-changed account, or the new
     * address a user asked to change to, which then replaces the old one.
     *
     * @param token the token from the verification email
     * @throws ApiException 400 if the token is unknown, already used or expired, or the change it confirms was
     *                      cancelled; 409 if another account took the new address meanwhile
     */
    @Transactional
    public void verifyEmail(String token) {
        AccountToken used = consume(token, AccountTokenType.EMAIL_VERIFICATION, AccountTokenType.EMAIL_CHANGE);
        if (used.getType() == AccountTokenType.EMAIL_VERIFICATION) {
            userService.markEmailVerified(used.getUserId());
        } else if (userService.applyPendingEmail(used.getUserId())) {
            // Links sent to the old address must not reset the password of the account it no longer belongs to.
            tokens.deleteAllByUserIdAndType(used.getUserId(), AccountTokenType.PASSWORD_RESET);
            tokens.deleteAllByUserIdAndType(used.getUserId(), AccountTokenType.EMAIL_VERIFICATION);
        } else {
            throw ApiException.badRequest(ProblemType.INVALID_TOKEN, INVALID_TOKEN);
        }
    }

    /**
     * Emails the link that confirms a new address to that address, once the change request has committed.
     *
     * @param event identifies the user changing their address
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEmailChangeRequested(EmailChangeRequested event) {
        users.findById(event.userId())
                .filter(user -> user.getPendingEmail() != null)
                .ifPresent(user -> sendEmailChangeLink(user, false));
    }

    /**
     * Issues an email change token, replacing earlier ones, and emails it to the pending address.
     *
     * @param user     the user changing their address
     * @param deferred leave the sending to the outbox poller, for anonymous requests
     */
    private void sendEmailChangeLink(User user, boolean deferred) {
        Duration ttl = properties.verificationTokenTtl();
        mailer.sendEmailVerification(
                user.getPendingEmail(), issue(user.getId(), AccountTokenType.EMAIL_CHANGE, ttl), ttl, deferred);
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
     * @param user     the user to verify
     * @param deferred leave the sending to the outbox poller, for anonymous requests
     */
    private void sendVerification(User user, boolean deferred) {
        Duration ttl = properties.verificationTokenTtl();
        mailer.sendEmailVerification(
                user.getEmail(), issue(user.getId(), AccountTokenType.EMAIL_VERIFICATION, ttl), ttl, deferred);
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
     * @param types the purposes it may have
     * @return the consumed token
     * @throws ApiException 400 if the token is unknown, already used or expired
     */
    private AccountToken consume(String token, AccountTokenType... types) {
        AccountToken stored = tokens.findByTokenHashAndTypeIn(SecureTokens.hash(token), List.of(types))
                .filter(candidate -> !candidate.isExpired(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest(ProblemType.INVALID_TOKEN, INVALID_TOKEN));
        tokens.deleteAllByUserIdAndType(stored.getUserId(), stored.getType());
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
