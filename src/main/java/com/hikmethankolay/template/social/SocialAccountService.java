package com.hikmethankolay.template.social;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.security.LoginAttemptService;
import com.hikmethankolay.template.social.dto.SocialAccountResponse;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps a provider account to a user of this application.
 *
 * <ol>
 *   <li>An account linked before logs in as its user.</li>
 *   <li>Otherwise the provider must vouch for the email address. If a user has that address and has verified
 *       it here too, the provider account is linked to them.</li>
 *   <li>If that user has not verified it, nothing is linked: whoever registered the address may not own it,
 *       and linking would hand them the real owner's sign-ins.</li>
 *   <li>Otherwise a new user is created with the address marked verified and a random password, which the
 *       user can replace through the password reset.</li>
 * </ol>
 */
@Service
public class SocialAccountService {

    private static final int MAX_BASE_LENGTH = 25;

    private final SocialIdentityRepository identities;

    private final UserRepository users;

    private final UserService userService;

    private final AuditLog audit;

    private final SecurityMailer securityMailer;

    private final LoginAttemptService loginAttempts;

    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service.
     *
     * @param identities      linked provider accounts
     * @param users           looks users up by email
     * @param userService     creates users
     * @param audit           records security events
     * @param securityMailer  tells a user when a provider account is linked to or unlinked from theirs
     * @param loginAttempts   counts wrong passwords entered to unlink an account as failed logins
     * @param passwordEncoder checks the password when unlinking an account
     */
    public SocialAccountService(
            SocialIdentityRepository identities,
            UserRepository users,
            UserService userService,
            AuditLog audit,
            SecurityMailer securityMailer,
            LoginAttemptService loginAttempts,
            PasswordEncoder passwordEncoder) {
        this.identities = identities;
        this.users = users;
        this.userService = userService;
        this.audit = audit;
        this.securityMailer = securityMailer;
        this.loginAttempts = loginAttempts;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Returns the user a provider account logs in as, linking or creating one on first use.
     *
     * @param profile the provider's view of the user
     * @return the user id
     * @throws ApiException 403 if the provider has no verified email for the account, 409 if the email belongs
     *                      to a user who has not verified it
     */
    @Transactional
    public long userIdFor(SocialProfile profile) {
        Optional<SocialIdentity> linked = identities.findByProviderAndSubject(profile.provider(), profile.subject());
        if (linked.isPresent()) {
            return linked.get().getUserId();
        }
        if (profile.email() == null || !profile.emailVerified()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ProblemType.SOCIAL_EMAIL_UNVERIFIED,
                    "The provider account has no verified email address.");
        }
        String email = profile.email().toLowerCase(Locale.ROOT);
        Optional<Long> existingId = users.findByEmail(email).map(existing -> {
            if (!existing.isEmailVerified()) {
                throw ApiException.conflict(
                        ProblemType.SOCIAL_ACCOUNT_CONFLICT,
                        "An account with this email exists; log in with its password and verify the email.");
            }
            return existing.getId();
        });
        long userId = existingId.orElseGet(() -> userService
                .register(freeUsername(profile, email), email, SecureTokens.generate(), Role.USER, true)
                .id());
        identities.save(new SocialIdentity(userId, profile.provider(), profile.subject()));
        audit.record(AuditEvent.SOCIAL_ACCOUNT_LINKED, userId, "provider", profile.provider());
        if (existingId.isPresent()) {
            // A new way into an existing account; an account created just now needs no warning.
            securityMailer.socialAccountLinked(email, profile.provider());
        }
        return userId;
    }

    /**
     * Lists the provider accounts linked to a user.
     *
     * @param userId the user
     * @return the links, oldest first
     */
    @Transactional(readOnly = true)
    public List<LinkedAccount> linkedAccounts(long userId) {
        return identities.findAllByUserIdOrderByIdAsc(userId).stream()
                .map(identity ->
                        new LinkedAccount(identity.getProvider(), identity.getSubject(), identity.getCreatedAt()))
                .toList();
    }

    /**
     * Lists the provider accounts linked to a user, without the provider's user ids.
     *
     * @param userId the user
     * @return the links, oldest first
     */
    @Transactional(readOnly = true)
    public List<SocialAccountResponse> list(long userId) {
        return identities.findAllByUserIdOrderByIdAsc(userId).stream()
                .map(identity ->
                        new SocialAccountResponse(identity.getId(), identity.getProvider(), identity.getCreatedAt()))
                .toList();
    }

    /**
     * Unlinks a provider account from the user, so it can no longer sign in to theirs.
     *
     * <p>The password is required, so this can never leave the account without a way to sign in: whoever passes the
     * check can sign in with the password. A user created by a social login has a random password and sets one with
     * the password reset first.</p>
     *
     * @param userId          the caller
     * @param id              the link's id
     * @param currentPassword the caller's password
     * @throws ApiException 404 if the user has no link with this id, 400 if the password is wrong, 429 if failed
     *                      passwords locked the IP or account, 503 if the failed-login counters cannot be checked
     */
    @Transactional
    public void unlink(long userId, long id, String currentPassword) {
        SocialIdentity identity = identities
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound(
                        ProblemType.SOCIAL_ACCOUNT_NOT_FOUND, "You have no linked account with this id."));
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound(ProblemType.USER_NOT_FOUND, "User not found."));
        // Wrong passwords count as failed logins, so a stolen access token cannot be used to guess the password here.
        if (!loginAttempts.checkReentered(
                userId, () -> passwordEncoder.matches(currentPassword, user.getPasswordHash()))) {
            throw ApiException.badRequest(ProblemType.WRONG_PASSWORD, "Current password is incorrect.");
        }
        identities.delete(identity);
        audit.record(AuditEvent.SOCIAL_ACCOUNT_UNLINKED, userId, "provider", identity.getProvider());
        securityMailer.socialAccountUnlinked(user.getEmail(), identity.getProvider());
    }

    /** The provider's name or the email's local part, cleaned up to the username rules and made unique. */
    private String freeUsername(SocialProfile profile, String email) {
        String base = (profile.username() != null ? profile.username() : email.substring(0, email.indexOf('@')))
                .replaceAll("[^A-Za-z0-9._-]", "");
        if (base.length() < 3) {
            base = "user" + base;
        }
        base = base.substring(0, Math.min(base.length(), MAX_BASE_LENGTH));
        String candidate = base;
        while (users.existsByUsername(candidate)) {
            candidate = base + "-" + SecureTokens.generate().substring(0, 6);
        }
        return candidate;
    }

    /**
     * A provider account linked to a user, as shown in the personal data export.
     *
     * @param provider registration id
     * @param subject  the provider's user id
     * @param linkedAt when it was linked
     */
    public record LinkedAccount(String provider, String subject, Instant linkedAt) {}
}
