package com.hikmethankolay.template.mfa;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.config.CacheConfig.CacheNames;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.mfa.dto.RecoveryCodesResponse;
import com.hikmethankolay.template.mfa.dto.TotpEnrollmentResponse;
import com.hikmethankolay.template.security.LoginAttemptService;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-factor authentication with an authenticator app: enrollment, confirmation, recovery codes, turning it off,
 * and checking the second factor at login.
 *
 * <p>Enrollment is two-step: {@link #enroll(long)} stores a new encrypted secret, and only
 * {@link #confirm(long, String, String)} with a valid code turns two-factor authentication on, so a user who never
 * finishes setting up the app is not locked out.</p>
 */
@Service
@Transactional(readOnly = true)
public class MfaService {

    private static final int TOTP_DIGITS = 6;

    private static final int RECOVERY_CODE_COUNT = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;

    private final RecoveryCodeRepository recoveryCodes;

    private final SecretCipher cipher;

    private final PasswordEncoder passwordEncoder;

    private final AuditLog audit;

    private final LoginAttemptService loginAttempts;

    private final SecurityMailer securityMailer;

    private final String issuer;

    /**
     * Creates the service.
     *
     * @param users           user data access
     * @param recoveryCodes   recovery code data access
     * @param cipher          encrypts the secrets
     * @param passwordEncoder checks the password when turning two-factor authentication off
     * @param audit           records security events
     * @param loginAttempts   locks out repeated wrong passwords and codes
     * @param securityMailer  tells users when two-factor authentication is turned on or off
     * @param issuer          application name shown in authenticator apps
     */
    public MfaService(
            UserRepository users,
            RecoveryCodeRepository recoveryCodes,
            SecretCipher cipher,
            PasswordEncoder passwordEncoder,
            AuditLog audit,
            LoginAttemptService loginAttempts,
            SecurityMailer securityMailer,
            @Value("${spring.application.name}") String issuer) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.loginAttempts = loginAttempts;
        this.securityMailer = securityMailer;
        this.issuer = issuer;
    }

    /**
     * Creates a new authenticator secret for the user. Calling it again before confirming replaces the secret.
     *
     * @param userId the caller
     * @return the secret and the URI for the QR code
     * @throws ApiException 409 if two-factor authentication is already on
     */
    @Transactional
    public TotpEnrollmentResponse enroll(long userId) {
        User user = findUser(userId);
        if (user.isTotpEnabled()) {
            throw ApiException.conflict(ProblemType.TOTP_ALREADY_ENABLED, "Two-factor authentication is already on.");
        }
        byte[] secret = Totp.generateSecret();
        user.startTotpEnrollment(cipher.encrypt(secret, userId));
        return new TotpEnrollmentResponse(Totp.base32(secret), Totp.otpauthUri(issuer, user.getUsername(), secret));
    }

    /**
     * Turns two-factor authentication on with the first code from the app and creates the recovery codes.
     *
     * @param userId          the caller
     * @param currentPassword the caller's password: with a stolen token alone, an attacker could otherwise
     *                        enroll their own app and lock the user out
     * @param code            the code shown by the app
     * @return the recovery codes, which are not stored in readable form and cannot be shown again
     * @throws ApiException 409 if it is already on or no enrollment is pending, 400 if the password or the code
     *                      is wrong
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#userId")
    public RecoveryCodesResponse confirm(long userId, String currentPassword, String code) {
        User user = findUser(userId);
        if (user.isTotpEnabled()) {
            throw ApiException.conflict(ProblemType.TOTP_ALREADY_ENABLED, "Two-factor authentication is already on.");
        }
        if (user.getTotpSecret() == null) {
            throw ApiException.conflict(ProblemType.TOTP_NOT_ENABLED, "Start the enrollment first.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest(ProblemType.WRONG_PASSWORD, "Current password is incorrect.");
        }
        OptionalLong step =
                Totp.verify(secretOf(user), normalize(code), Instant.now().getEpochSecond(), 0);
        if (step.isEmpty()) {
            throw ApiException.badRequest(ProblemType.INVALID_MFA_CODE, "The code is wrong or expired.");
        }
        user.enableTotp(step.getAsLong());
        recoveryCodes.deleteAllByUserId(userId);
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String recoveryCode = newRecoveryCode();
            codes.add(recoveryCode);
            recoveryCodes.save(new RecoveryCode(userId, SecureTokens.hash(normalize(recoveryCode))));
        }
        audit.record(AuditEvent.MFA_ENABLED, userId);
        securityMailer.twoFactorEnabled(user.getEmail());
        return new RecoveryCodesResponse(codes);
    }

    /**
     * Turns two-factor authentication off and deletes the secret and the recovery codes.
     *
     * <p>Wrong passwords and codes count as failed logins of the IP and the account, so a stolen token cannot be
     * used to guess the code here instead of at login.</p>
     *
     * @param userId          the caller
     * @param currentPassword the caller's password
     * @param code            an authenticator code or an unused recovery code
     * @param clientIp        address of the client, used for the lockout
     * @throws ApiException 409 if it is off, 400 if the password or the code is wrong or the password is too short
     *                      to keep without a second factor, 429 if the IP or account is blocked, 503 if the
     *                      failed-login counters cannot be checked
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.USERS, key = "#userId")
    public void disable(long userId, String currentPassword, String code, String clientIp) {
        User user = findUser(userId);
        if (!user.isTotpEnabled()) {
            throw ApiException.conflict(ProblemType.TOTP_NOT_ENABLED, "Two-factor authentication is off.");
        }
        // 8 characters are allowed only with a second factor; without one the password needs 15. Checked before
        // anything is verified or counted, so no code (or recovery code) is used up by a refused request.
        if (!UserService.meetsSingleFactorLength(currentPassword)) {
            throw ApiException.badRequest(
                    ProblemType.PASSWORD_TOO_SHORT,
                    "Change your password to one with at least " + UserService.MIN_PASSWORD_LENGTH
                            + " characters before turning off two-factor authentication.");
        }
        // The same keys as AuthService, so these failures and failed logins share one limit.
        String accountKey = "account:" + userId;
        String ipKey = "ip:" + ClientIp.limitKey(clientIp);
        if (!loginAttempts.tryAcquire(ipKey, accountKey)) {
            throw ApiException.tooManyRequests(
                    ProblemType.LOGIN_LOCKED,
                    "Too many failed attempts. Please try again later.",
                    loginAttempts.lockDuration());
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest(ProblemType.WRONG_PASSWORD, "Current password is incorrect.");
        }
        if (!checkCode(user, code)) {
            throw ApiException.badRequest(ProblemType.INVALID_MFA_CODE, "The code is wrong, expired or used.");
        }
        loginAttempts.reset(accountKey);
        loginAttempts.release(ipKey);
        user.disableTotp();
        recoveryCodes.deleteAllByUserId(userId);
        audit.record(AuditEvent.MFA_DISABLED, userId);
        securityMailer.twoFactorDisabled(user.getEmail());
    }

    /**
     * Checks the second factor at login. A code is accepted once: the authenticator code's time step, or the
     * recovery code, is used up.
     *
     * @param userId the user logging in
     * @param code   an authenticator code or a recovery code
     * @return {@code true} if the code is valid; {@code false} also if the user was deleted or turned two-factor
     *     authentication off since the password was checked
     */
    @Transactional
    public boolean verifyLogin(long userId, String code) {
        return users.findById(userId)
                .map(user -> user.isTotpEnabled() && checkCode(user, code))
                .orElse(false);
    }

    private boolean checkCode(User user, String code) {
        String normalized = normalize(code);
        if (normalized.length() == TOTP_DIGITS && normalized.chars().allMatch(Character::isDigit)) {
            OptionalLong step;
            try {
                step = Totp.verify(secretOf(user), normalized, Instant.now().getEpochSecond(), user.getTotpLastStep());
            } catch (IllegalStateException ex) {
                // The encryption key changed: only recovery codes can still get the user in.
                return false;
            }
            step.ifPresent(user::useTotpStep);
            return step.isPresent();
        }
        boolean used = recoveryCodes.markUsed(user.getId(), SecureTokens.hash(normalized), Instant.now()) == 1;
        if (used) {
            audit.record(AuditEvent.RECOVERY_CODE_USED, user.getId());
        }
        return used;
    }

    private byte[] secretOf(User user) {
        return cipher.decrypt(user.getTotpSecret(), user.getId());
    }

    private User findUser(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> ApiException.notFound(ProblemType.USER_NOT_FOUND, "User not found."));
    }

    /** Codes are typed by people: ignore spaces, dashes and case. */
    private static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    /** Ten Base32 characters (50 bits), shown as {@code xxxxx-xxxxx}. */
    private static String newRecoveryCode() {
        byte[] bytes = new byte[7];
        RANDOM.nextBytes(bytes);
        String code = Totp.base32(bytes).substring(0, 10).toLowerCase(Locale.ROOT);
        return code.substring(0, 5) + "-" + code.substring(5);
    }
}
