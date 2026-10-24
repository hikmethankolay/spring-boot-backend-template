package com.hikmethankolay.template.auth;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.auth.dto.LoginRequest;
import com.hikmethankolay.template.auth.dto.RegisterRequest;
import com.hikmethankolay.template.auth.dto.SocialProviderResponse;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.mfa.MfaService;
import com.hikmethankolay.template.security.AppUserDetails;
import com.hikmethankolay.template.security.LoginAttemptService;
import com.hikmethankolay.template.security.TokenRevocationService;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.social.SocialAccountService;
import com.hikmethankolay.template.social.SocialLoginProviders;
import com.hikmethankolay.template.social.SocialProfileClient;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import com.hikmethankolay.template.user.dto.UserResponse;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Registration, login (password or GitHub/Google, then the second factor if enabled), token refresh and logout.
 *
 * <p>Login returns a short-lived access token (JWT) and a refresh token; refresh exchanges the
 * refresh token for a new pair (see {@link RefreshTokenService} for rotation and reuse detection).
 * Users with two-factor authentication get an MFA token instead, and the tokens from
 * {@link #loginWithMfa(String, String, String)}.</p>
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;

    private final LoginAttemptService loginAttempts;

    private final TokenService tokenService;

    private final UserService userService;

    private final UserRepository userRepository;

    private final RefreshTokenService refreshTokens;

    private final TokenRevocationService revocations;

    private final JwtDecoder jwtDecoder;

    private final boolean requireVerifiedEmail;

    private final AuditLog audit;

    private final MfaService mfa;

    private final MfaChallengeService challenges;

    private final SocialLoginProviders socialProviders;

    private final SocialProfileClient socialProfiles;

    private final SocialAccountService socialAccounts;

    /**
     * Creates the service.
     *
     * @param authenticationManager verifies credentials
     * @param loginAttempts         brute-force protection
     * @param tokenService          issues JWTs
     * @param userService           creates users
     * @param userRepository        reloads users on refresh
     * @param refreshTokens         issues and rotates refresh tokens
     * @param revocations           denylist of revoked access tokens
     * @param jwtDecoder            validates the token presented at logout
     * @param properties            application properties (email verification policy)
     * @param audit                 records security events
     * @param mfa                   checks second-factor codes
     * @param challenges            logins waiting for their second factor
     * @param socialProviders       the configured login providers
     * @param socialProfiles        exchanges provider codes for profiles
     * @param socialAccounts        maps provider accounts to users
     */
    public AuthService(
            AuthenticationManager authenticationManager,
            LoginAttemptService loginAttempts,
            TokenService tokenService,
            UserService userService,
            UserRepository userRepository,
            RefreshTokenService refreshTokens,
            TokenRevocationService revocations,
            JwtDecoder jwtDecoder,
            AppProperties properties,
            AuditLog audit,
            MfaService mfa,
            MfaChallengeService challenges,
            SocialLoginProviders socialProviders,
            SocialProfileClient socialProfiles,
            SocialAccountService socialAccounts) {
        this.authenticationManager = authenticationManager;
        this.loginAttempts = loginAttempts;
        this.tokenService = tokenService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.refreshTokens = refreshTokens;
        this.revocations = revocations;
        this.jwtDecoder = jwtDecoder;
        this.requireVerifiedEmail = properties.account().requireVerifiedEmail();
        this.audit = audit;
        this.mfa = mfa;
        this.challenges = challenges;
        this.socialProviders = socialProviders;
        this.socialProfiles = socialProfiles;
        this.socialAccounts = socialAccounts;
    }

    /**
     * Registers a new user with the {@link Role#USER} role and sends a verification email.
     *
     * @param request registration data
     * @return the created user
     * @throws ApiException 409 if the username or email is already taken
     */
    public UserResponse register(RegisterRequest request) {
        return userService.register(request.username(), request.email(), request.password(), Role.USER, false);
    }

    /**
     * Verifies credentials and starts a session.
     *
     * <p>Failures are counted per client IP and per account. Once either is blocked, logins are
     * rejected with 429 until the lock expires, even with the correct password.</p>
     *
     * @param request  username or email, password and remember-me flag
     * @param clientIp address of the client, used for rate limiting
     * @return the access and refresh tokens, or an MFA token if the user has two-factor authentication
     * @throws ApiException            429 if the IP or account is blocked, 503 if the failed-login
     *                                 counters cannot be checked
     * @throws BadCredentialsException if the credentials are wrong (mapped to 401)
     */
    public LoginResult login(LoginRequest request, String clientIp) {
        String identifier = request.identifier().trim();
        Long accountId = userRepository
                .findByUsernameOrEmail(identifier, identifier.toLowerCase(Locale.ROOT))
                .map(User::getId)
                .orElse(null);
        // Keyed by user id, so the username and the email of one account share a single limit.
        String accountKey =
                "account:" + (accountId != null ? accountId.toString() : identifier.toLowerCase(Locale.ROOT));
        String ipKey = "ip:" + ClientIp.limitKey(clientIp);
        // Users sometimes type their password into the identifier field: audit a short hash, never the text.
        String identifierHash =
                SecureTokens.hash(identifier.toLowerCase(Locale.ROOT)).substring(0, 12);

        if (!loginAttempts.tryAcquire(ipKey, accountKey)) {
            audit.record(AuditEvent.LOGIN_BLOCKED, accountId, "identifierHash", identifierHash);
            throw ApiException.tooManyRequests(
                    ProblemType.LOGIN_LOCKED,
                    "Too many failed login attempts. Please try again later.",
                    loginAttempts.lockDuration());
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(identifier, request.password()));
        } catch (AuthenticationException ex) {
            // The attempt was already counted by tryAcquire.
            audit.record(AuditEvent.LOGIN_FAILED, accountId, "identifierHash", identifierHash);
            throw new BadCredentialsException("Invalid username or password.");
        }

        AppUserDetails user = (AppUserDetails) Objects.requireNonNull(authentication.getPrincipal());
        boolean mfaEnabled =
                userRepository.findById(user.id()).map(User::isTotpEnabled).orElse(false);
        // Only the account counter is reset: one valid login must not clear the IP's failures, it
        // only takes back its own attempt. With a second factor, the account's failures stay until the code
        // is right, so wrong codes add up across logins.
        if (mfaEnabled) {
            loginAttempts.release(accountKey);
        } else {
            loginAttempts.reset(accountKey);
        }
        loginAttempts.release(ipKey);
        // Checked only after the password, so the response does not reveal whether an account exists.
        if (requireVerifiedEmail && !user.emailVerified()) {
            audit.record(AuditEvent.LOGIN_UNVERIFIED, user.id());
            throw new ApiException(
                    HttpStatus.FORBIDDEN, ProblemType.EMAIL_NOT_VERIFIED, "Email address is not verified.");
        }
        return startSession(
                user.id(),
                user.username(),
                user.role(),
                user.sessionVersion(),
                mfaEnabled,
                request.isRememberMe(),
                "password");
    }

    /**
     * Finishes a login with the second factor.
     *
     * <p>Wrong codes count as failed logins of the IP and the account, so guessing codes runs into the same
     * lockout as guessing passwords, however many MFA tokens are requested.</p>
     *
     * @param mfaToken the token from the login response
     * @param code     an authenticator code or a recovery code
     * @param clientIp address of the client, used for rate limiting
     * @return the access and refresh tokens
     * @throws ApiException 401 if the token is unknown or expired or the code is wrong, 429 if the IP or
     *                      account is blocked, 503 if Redis is unavailable
     */
    public AuthTokens loginWithMfa(String mfaToken, String code, String clientIp) {
        MfaChallengeService.Challenge challenge =
                challenges.find(mfaToken).orElseThrow(AuthService::mfaChallengeExpired);
        String accountKey = "account:" + challenge.userId();
        String ipKey = "ip:" + ClientIp.limitKey(clientIp);
        if (!loginAttempts.tryAcquire(ipKey, accountKey)) {
            audit.record(AuditEvent.LOGIN_BLOCKED, challenge.userId());
            throw ApiException.tooManyRequests(
                    ProblemType.LOGIN_LOCKED,
                    "Too many failed login attempts. Please try again later.",
                    loginAttempts.lockDuration());
        }
        if (!mfa.verifyLogin(challenge.userId(), code)) {
            audit.record(AuditEvent.MFA_FAILED, challenge.userId());
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, ProblemType.INVALID_MFA_CODE, "The code is wrong, expired or used.");
        }
        if (!challenges.consume(mfaToken)) {
            throw mfaChallengeExpired();
        }
        loginAttempts.reset(accountKey);
        loginAttempts.release(ipKey);
        User user = userRepository.findById(challenge.userId()).orElseThrow(AuthService::mfaChallengeExpired);
        return issueTokens(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                challenge.sessionVersion(),
                challenge.rememberMe(),
                "mfa");
    }

    /**
     * Logs in with an authorization code from GitHub or Google, creating or linking the user on first use
     * (see {@link SocialAccountService}).
     *
     * @param provider     registration id from the path
     * @param code         the authorization code the provider sent to the frontend
     * @param codeVerifier the PKCE code verifier, or {@code null}
     * @return the tokens, or an MFA token if the user has two-factor authentication
     * @throws ApiException            404 if the provider is not configured, 403 or 409 if the account cannot
     *                                 be linked
     * @throws BadCredentialsException if the provider rejects the code (mapped to 401)
     */
    public LoginResult socialLogin(String provider, String code, String codeVerifier) {
        ClientRegistration registration = socialProviders
                .find(provider)
                .orElseThrow(() -> ApiException.notFound(
                        ProblemType.PROVIDER_NOT_CONFIGURED, "This login provider is not configured."));
        long userId = socialAccounts.userIdFor(socialProfiles.fetch(registration, code, codeVerifier));
        // Just found or created in the same request.
        User user = userRepository.findById(userId).orElseThrow();
        return startSession(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getSessionVersion(),
                user.isTotpEnabled(),
                false,
                provider);
    }

    /**
     * Lists the configured login providers.
     *
     * @return one entry per provider with credentials, possibly none
     */
    public List<SocialProviderResponse> socialProviders() {
        return socialProviders.authorizationUris().entrySet().stream()
                .map(entry -> new SocialProviderResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private LoginResult startSession(
            long userId,
            String username,
            Role role,
            int sessionVersion,
            boolean mfaEnabled,
            boolean rememberMe,
            String method) {
        if (mfaEnabled) {
            String token = challenges.create(new MfaChallengeService.Challenge(userId, sessionVersion, rememberMe));
            return new LoginResult.MfaRequired(token, challenges.ttl());
        }
        return issueTokens(userId, username, role, sessionVersion, rememberMe, method);
    }

    private AuthTokens issueTokens(
            long userId, String username, Role role, int sessionVersion, boolean rememberMe, String method) {
        // Issued first: it fails if the password or role changed since it was checked.
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokens.issue(userId, sessionVersion, rememberMe);
        audit.record(AuditEvent.LOGIN_SUCCEEDED, userId, "rememberMe", rememberMe, "method", method);
        return new AuthTokens(tokenService.issue(userId, username, role, refreshToken.sessionId()), refreshToken);
    }

    private static ApiException mfaChallengeExpired() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, ProblemType.MFA_CHALLENGE_EXPIRED, "The MFA token is unknown or expired.");
    }

    /**
     * Exchanges a refresh token for a new access token and refresh token. The access token picks
     * up role and username changes since the last login.
     *
     * @param refreshToken the refresh token presented by the client, may be {@code null}
     * @return the new tokens
     * @throws BadCredentialsException if the refresh token is missing, invalid, expired or reused (mapped to 401)
     */
    public AuthTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException(RefreshTokenService.INVALID_REFRESH_TOKEN);
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken);
        // The user cannot be missing: deleting a user deletes its refresh tokens.
        return userRepository
                .findById(rotation.userId())
                .map(user -> new AuthTokens(
                        tokenService.issue(
                                user.getId(),
                                user.getUsername(),
                                user.getRole(),
                                rotation.successor().sessionId()),
                        rotation.successor()))
                .orElseThrow(() -> new BadCredentialsException(RefreshTokenService.INVALID_REFRESH_TOKEN));
    }

    /**
     * Ends a session: revokes the access token and the refresh token's session. Invalid, expired
     * or missing tokens are ignored: there is nothing left to revoke.
     *
     * @param accessToken  the raw access token from the header or cookie, may be {@code null}
     * @param refreshToken the refresh token from the body or cookie, may be {@code null}
     */
    public void logout(String accessToken, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revokeSession(refreshToken);
        }
        if (accessToken == null) {
            return;
        }
        try {
            Jwt jwt = jwtDecoder.decode(accessToken);
            revocations.revoke(jwt);
            UUID sessionId = TokenService.sessionId(jwt);
            if (sessionId != null) {
                refreshTokens.endSession(TokenService.userId(jwt), sessionId);
            }
            audit.record(AuditEvent.LOGOUT, TokenService.userId(jwt));
        } catch (JwtException ex) {
            // Already invalid, expired or revoked.
        }
    }
}
