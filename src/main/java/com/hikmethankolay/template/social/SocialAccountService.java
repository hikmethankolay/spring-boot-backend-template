package com.hikmethankolay.template.social;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
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

    /**
     * Creates the service.
     *
     * @param identities     linked provider accounts
     * @param users          looks users up by email
     * @param userService    creates users
     * @param audit          records security events
     * @param securityMailer tells a user when a provider account is linked to theirs
     */
    public SocialAccountService(
            SocialIdentityRepository identities,
            UserRepository users,
            UserService userService,
            AuditLog audit,
            SecurityMailer securityMailer) {
        this.identities = identities;
        this.users = users;
        this.userService = userService;
        this.audit = audit;
        this.securityMailer = securityMailer;
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
