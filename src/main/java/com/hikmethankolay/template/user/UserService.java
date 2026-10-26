package com.hikmethankolay.template.user;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SortAllowList;
import com.hikmethankolay.template.config.CacheConfig.CacheNames;
import com.hikmethankolay.template.user.dto.AdminUpdateUserRequest;
import com.hikmethankolay.template.user.dto.ChangePasswordRequest;
import com.hikmethankolay.template.user.dto.DeleteAccountRequest;
import com.hikmethankolay.template.user.dto.UpdateProfileRequest;
import com.hikmethankolay.template.user.dto.UserResponse;
import com.hikmethankolay.template.user.validation.BreachedPasswordValidator;
import java.util.List;
import java.util.Locale;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User management: registration, lookups, profile and password changes, administration.
 *
 * <p>Methods return DTOs so entities never leave the transaction. Errors are reported with
 * {@link ApiException}: 404 for unknown users, 409 for duplicate usernames or emails,
 * 400 for invalid operations.</p>
 *
 * <p>Changing a password or role ends all of the user's sessions (see {@link SessionsInvalidated}).
 * A new password needs {@value #MIN_PASSWORD_LENGTH} characters, or 8 (the {@code @ValidPassword}
 * floor) for users with two-factor authentication, and must not be a common or breached password
 * (NIST SP 800-63B-4).</p>
 *
 * <p>Lookups by id are cached in {@link CacheNames#USERS}; every method that changes a user
 * evicts its entry. Evictions take effect after the transaction commits (see {@code CacheConfig}).</p>
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    /**
     * Properties users can be sorted by: only fields already visible in {@link UserResponse}, never
     * {@code passwordHash}. Listed in the {@code sort} parameter description of the API.
     */
    public static final String SORTABLE_PROPERTIES = "id, username, email, role, emailVerified, createdAt, updatedAt";

    /** Characters a new password needs without two-factor authentication (NIST SP 800-63B-4). */
    public static final int MIN_PASSWORD_LENGTH = 15;

    private static final SortAllowList SORTING = new SortAllowList(List.of(SORTABLE_PROPERTIES.split(", ")));

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final BreachedPasswordValidator breachedPasswords;

    private final ApplicationEventPublisher events;

    private final AuditLog audit;

    /**
     * Creates the service.
     *
     * @param userRepository    user persistence
     * @param passwordEncoder   hashes and verifies passwords
     * @param breachedPasswords rejects common and breached passwords
     * @param events            publishes user events
     * @param audit             records security events
     */
    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            BreachedPasswordValidator breachedPasswords,
            ApplicationEventPublisher events,
            AuditLog audit) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.breachedPasswords = breachedPasswords;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Creates a user. Unverified users get a verification email after the transaction commits.
     *
     * @param username      unique username
     * @param email         unique email; stored trimmed and in lower case
     * @param rawPassword   plain password, stored hashed
     * @param role          role of the new user
     * @param emailVerified whether the address is already trusted (e.g. the configured admin)
     * @return the created user
     * @throws ApiException 400 if the password is too short or breached, 409 if the username or email is
     *                      already taken
     */
    @Transactional
    public UserResponse register(String username, String email, String rawPassword, Role role, boolean emailVerified) {
        String normalizedEmail = normalizeEmail(email);
        ensureUsernameAvailable(username);
        ensureEmailAvailable(normalizedEmail);
        return create(username, normalizedEmail, encodeNewPassword(false, rawPassword), role, emailVerified);
    }

    /**
     * Creates a {@link Role#USER} whose address was just confirmed, from a self-service sign-up.
     *
     * @param username        the requested username
     * @param normalizedEmail the confirmed, normalised email
     * @param passwordHash    the password, encoded when the user signed up
     * @return the created user
     * @throws ApiException 409 if the username or email was taken since the sign-up
     */
    @Transactional
    public UserResponse registerConfirmed(String username, String normalizedEmail, String passwordHash) {
        ensureUsernameAvailable(username);
        ensureEmailAvailable(normalizedEmail);
        return create(username, normalizedEmail, passwordHash, Role.USER, true);
    }

    /**
     * Stores a new user. Unverified users get a verification email after the transaction commits.
     *
     * @param username        checked username
     * @param normalizedEmail checked, normalised email
     * @param passwordHash    encoded password
     * @param role            role of the new user
     * @param emailVerified   whether the address is already trusted
     * @return the created user
     */
    private UserResponse create(
            String username, String normalizedEmail, String passwordHash, Role role, boolean emailVerified) {
        User user = new User(username, normalizedEmail, passwordHash, role);
        user.setEmailVerified(emailVerified);
        User saved = userRepository.save(user);
        audit.record(AuditEvent.USER_REGISTERED, saved.getId(), "role", role);
        if (!emailVerified) {
            events.publishEvent(new EmailVerificationRequired(saved.getId()));
        }
        return UserResponse.from(saved);
    }

    /**
     * Marks a user's email address as verified.
     *
     * @param id user id
     * @throws ApiException 404 if the user does not exist
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public void markEmailVerified(long id) {
        findUser(id).setEmailVerified(true);
        audit.record(AuditEvent.EMAIL_VERIFIED, id);
    }

    /**
     * Sets a new password without checking the old one, e.g. after a verified password reset.
     * Ends all of the user's sessions.
     *
     * @param id          user id
     * @param rawPassword the new plain password, stored hashed
     * @throws ApiException 400 if the password is too short or breached, 404 if the user does not exist
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public void setPassword(long id, String rawPassword) {
        User user = findUser(id);
        user.setPasswordHash(encodeNewPassword(user.isTotpEnabled(), rawPassword));
        endSessions(user);
    }

    /**
     * Looks up a user.
     *
     * @param id user id
     * @return the user
     * @throws ApiException 404 if the user does not exist (not cached)
     */
    @Cacheable(cacheNames = CacheNames.USERS, key = "#id")
    public UserResponse getById(long id) {
        return UserResponse.from(findUser(id));
    }

    /**
     * Lists users page by page.
     *
     * @param pageable page number, size and sort order
     * @return one page of users
     * @throws ApiException 400 if the sort order uses a property outside {@link #SORTABLE_PROPERTIES}
     */
    public Page<UserResponse> list(Pageable pageable) {
        return userRepository.findAll(SORTING.check(pageable)).map(UserResponse::from);
    }

    /**
     * Tells whether the owner account exists.
     *
     * @return {@code true} if a user has the {@link Role#OWNER} role
     */
    public boolean ownerExists() {
        return userRepository.existsByRole(Role.OWNER);
    }

    /**
     * Updates the username and/or email of a user; {@code null} fields are left unchanged. The
     * email is where password resets go, so changing it needs the current password, like a
     * password change does.
     *
     * @param id      user id
     * @param request the changes
     * @return the updated user
     * @throws ApiException 400 if the email changes without the correct current password,
     *                      404 if the user does not exist, 409 if the new username or email is taken
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public UserResponse updateProfile(long id, UpdateProfileRequest request) {
        User user = findUser(id);
        if (request.email() != null && !normalizeEmail(request.email()).equals(user.getEmail())) {
            confirmPassword(user, request.currentPassword());
        }
        applyIdentityChanges(user, request.username(), request.email());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    /**
     * Changes a user's password after verifying the current one. Ends all of the user's sessions,
     * including the current one: the client must log in again.
     *
     * @param id      user id
     * @param request current and new password
     * @throws ApiException 404 if the user does not exist, 400 if the current password is wrong or the
     *                      new one is too short or breached
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public void changePassword(long id, ChangePasswordRequest request) {
        User user = findUser(id);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest(ProblemType.WRONG_PASSWORD, "Current password is incorrect.");
        }
        user.setPasswordHash(encodeNewPassword(user.isTotpEnabled(), request.newPassword()));
        audit.record(AuditEvent.PASSWORD_CHANGED, id);
        endSessions(user);
    }

    /**
     * Updates any field of a user as an administrator; {@code null} fields are left unchanged.
     * A new password or role ends all of the user's sessions, so it takes effect immediately.
     *
     * <p>Administrators manage regular users. Changing a role, or changing an administrator or the
     * owner, needs the owner, and a role change must be confirmed with the owner's current password.
     * Nobody can demote the owner or make someone owner.</p>
     *
     * @param id          user id
     * @param requesterId id of the administrator making the request
     * @param request     the changes
     * @return the updated user
     * @throws ApiException 400 if a role change is not confirmed with the requester's current password or
     *                      the new password is too short or breached, 403 if the change needs the owner, 404 if the user does not exist, 409 if the
     *                      new username or email is taken or the change would assign or remove the OWNER role
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public UserResponse update(long id, long requesterId, AdminUpdateUserRequest request) {
        User user = findUser(id);
        boolean roleChanged = request.role() != null && request.role() != user.getRole();
        if (roleChanged && (request.role() == Role.OWNER || user.getRole() == Role.OWNER)) {
            throw ApiException.conflict(ProblemType.OWNER_PROTECTED, "The OWNER role cannot be assigned or removed.");
        }
        if (roleChanged || user.getRole() != Role.USER) {
            User owner = requireOwner(requesterId, "Only the owner can change roles or administrators.");
            if (roleChanged) {
                confirmPassword(owner, request.currentPassword());
            }
        }
        String oldUsername = user.getUsername();
        String oldEmail = user.getEmail();
        applyIdentityChanges(user, request.username(), request.email());
        boolean identityChanged =
                !user.getUsername().equals(oldUsername) || !user.getEmail().equals(oldEmail);
        if (request.password() != null) {
            user.setPasswordHash(encodeNewPassword(user.isTotpEnabled(), request.password()));
        }
        if (identityChanged || request.password() != null) {
            audit.record(
                    AuditEvent.USER_UPDATED_BY_ADMIN,
                    id,
                    "identityChanged",
                    identityChanged,
                    "passwordChanged",
                    request.password() != null);
        }
        if (roleChanged) {
            audit.record(AuditEvent.ROLE_CHANGED, id, "from", user.getRole(), "to", request.role());
            user.setRole(request.role());
        }
        if (roleChanged || request.password() != null) {
            endSessions(user);
        }
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    /**
     * Deletes a user. Administrators delete regular users; deleting an administrator needs the owner,
     * confirmed with the owner's current password. Nobody can delete the owner.
     *
     * @param id           id of the user to delete
     * @param requesterId  id of the administrator making the request
     * @param confirmation the requester's current password; needed only to delete an administrator
     * @throws ApiException 400 if an administrator tries to delete their own account or the password is
     *                      missing or wrong, 403 if the user is an administrator and the requester is not
     *                      the owner, 404 if the user does not exist, 409 if the user is the owner
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public void delete(long id, long requesterId, DeleteAccountRequest confirmation) {
        if (id == requesterId) {
            throw ApiException.badRequest(ProblemType.OWN_ACCOUNT, "You cannot delete your own account.");
        }
        User user = findUser(id);
        if (user.getRole() == Role.OWNER) {
            throw ApiException.conflict(ProblemType.OWNER_PROTECTED, "The owner account cannot be deleted.");
        }
        if (user.getRole() == Role.ADMIN) {
            User owner = requireOwner(requesterId, "Only the owner can delete administrators.");
            confirmPassword(owner, confirmation != null ? confirmation.password() : null);
        }
        userRepository.delete(user);
        audit.record(AuditEvent.USER_DELETED_BY_ADMIN, id);
        events.publishEvent(new SessionsInvalidated(id));
    }

    /**
     * Deletes the caller's own account after checking the password. Data of other features goes
     * with it through {@code ON DELETE CASCADE}; the sessions end.
     *
     * @param id      the caller's user id
     * @param request the current password
     * @throws ApiException 400 if the password is wrong, 404 if the user does not exist,
     *                      409 if the user is the owner
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#id")
    public void deleteOwnAccount(long id, DeleteAccountRequest request) {
        User user = findUser(id);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.badRequest(ProblemType.WRONG_PASSWORD, "Password is incorrect.");
        }
        if (user.getRole() == Role.OWNER) {
            throw ApiException.conflict(ProblemType.OWNER_PROTECTED, "The owner account cannot be deleted.");
        }
        userRepository.delete(user);
        audit.record(AuditEvent.ACCOUNT_DELETED, id);
        events.publishEvent(new SessionsInvalidated(id));
    }

    /**
     * Ends every session of a user: refresh tokens issued under the old session version, even by a
     * login or refresh running right now, are rejected, and the {@code auth} package revokes the rest.
     *
     * @param user the user whose sessions end
     */
    private void endSessions(User user) {
        user.endSessions();
        events.publishEvent(new SessionsInvalidated(user.getId()));
    }

    /**
     * Loads the requester and checks that they are the owner. The role is read from the database, not
     * from the access token, so a demotion applies even before the token expires.
     *
     * @param requesterId id of the user making the request
     * @param detail      explanation sent to the client when the requester is not the owner
     * @return the owner
     * @throws ApiException 403 if the requester is not the owner
     */
    private User requireOwner(long requesterId, String detail) {
        User requester = findUser(requesterId);
        if (requester.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ProblemType.OWNER_REQUIRED, detail);
        }
        return requester;
    }

    /**
     * Tells whether a password is long enough for an account without two-factor authentication.
     *
     * @param password the plain password
     * @return {@code true} if it has at least {@link #MIN_PASSWORD_LENGTH} characters, counted as code points so a
     *     character outside the BMP counts once (NIST SP 800-63B-4)
     */
    public static boolean meetsSingleFactorLength(String password) {
        return password.codePointCount(0, password.length()) >= MIN_PASSWORD_LENGTH;
    }

    /**
     * Checks a new password against the length rule and the breached list, then hashes it.
     *
     * @param twoFactor   whether the user has two-factor authentication, which allows 8 characters
     * @param rawPassword the new plain password
     * @return the encoded password
     * @throws ApiException 400 if the password is too short or breached
     */
    public String encodeNewPassword(boolean twoFactor, String rawPassword) {
        if (!twoFactor && !meetsSingleFactorLength(rawPassword)) {
            throw ApiException.badRequest(
                    ProblemType.PASSWORD_TOO_SHORT,
                    "The password needs at least " + MIN_PASSWORD_LENGTH
                            + " characters, or 8 with two-factor authentication.");
        }
        if (breachedPasswords.isBreached(rawPassword)) {
            throw ApiException.badRequest(
                    ProblemType.PASSWORD_BREACHED, "This password is too common or appeared in a data breach.");
        }
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * Checks the current password a user sent to confirm a sensitive change.
     *
     * @param user     the user confirming the change
     * @param password the password they sent, may be {@code null}
     * @throws ApiException 400 if the password is missing or wrong
     */
    private void confirmPassword(User user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest(
                    ProblemType.WRONG_PASSWORD,
                    "Confirm this change with your current password; it is missing or incorrect.");
        }
    }

    /**
     * Loads a user entity.
     *
     * @param id user id
     * @return the entity
     * @throws ApiException 404 if the user does not exist
     */
    private User findUser(long id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(ProblemType.USER_NOT_FOUND, "User " + id + " not found."));
    }

    /**
     * Applies username and email changes after checking that they are free. A changed email is
     * marked unverified and triggers a new verification email.
     *
     * @param user     the entity to change
     * @param username new username, or {@code null} to keep the current one
     * @param email    new email, or {@code null} to keep the current one
     */
    private void applyIdentityChanges(User user, String username, String email) {
        if (username != null && !username.equals(user.getUsername())) {
            ensureUsernameAvailable(username);
            user.setUsername(username);
        }
        if (email != null) {
            String normalizedEmail = normalizeEmail(email);
            if (!normalizedEmail.equals(user.getEmail())) {
                ensureEmailAvailable(normalizedEmail);
                user.setEmail(normalizedEmail);
                // A new address must be confirmed again.
                user.setEmailVerified(false);
                events.publishEvent(new EmailVerificationRequired(user.getId()));
            }
        }
    }

    /**
     * Fails if a username is taken.
     *
     * @param username username to check
     * @throws ApiException 409 if the username is taken
     */
    private void ensureUsernameAvailable(String username) {
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict(ProblemType.USERNAME_TAKEN, "Username is already taken.");
        }
    }

    /**
     * Fails if an email is taken.
     *
     * @param email normalised email to check
     * @throws ApiException 409 if the email is taken
     */
    private void ensureEmailAvailable(String email) {
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict(ProblemType.EMAIL_TAKEN, "Email is already registered.");
        }
    }

    /**
     * Normalises an email for storage and comparison.
     *
     * @param email email as entered
     * @return the trimmed, lower-case email
     */
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
