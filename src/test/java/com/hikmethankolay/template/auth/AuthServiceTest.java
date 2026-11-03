package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.account.AccountService;
import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.auth.dto.LoginRequest;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.mfa.MfaService;
import com.hikmethankolay.template.security.AppUserDetails;
import com.hikmethankolay.template.security.LoginAttemptService;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.social.SocialAccountService;
import com.hikmethankolay.template.social.SocialLoginProviders;
import com.hikmethankolay.template.social.SocialProfile;
import com.hikmethankolay.template.social.SocialProfileClient;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests of how {@link AuthService} counts failed logins: wrong credentials count, server errors do not. */
class AuthServiceTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String IP_KEY = "ip:" + ClientIp.limitKey(CLIENT_IP);

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MfaService mfa = mock(MfaService.class);
    private final MfaChallengeService challenges = mock(MfaChallengeService.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final SocialLoginProviders socialProviders = mock(SocialLoginProviders.class);
    private final SocialProfileClient socialProfiles = mock(SocialProfileClient.class);
    private final SocialAccountService socialAccounts = mock(SocialAccountService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AuthService service = new AuthService(
            authenticationManager,
            loginAttempts,
            mock(TokenService.class),
            mock(AccountService.class),
            users,
            refreshTokens,
            mock(JwtDecoder.class),
            TestProperties.create(),
            audit,
            mfa,
            challenges,
            socialProviders,
            socialProfiles,
            socialAccounts,
            mock(KnownDevices.class));

    @BeforeEach
    void allowAttempts() {
        when(loginAttempts.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(loginAttempts.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(users.findByUsernameOrEmail(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void wrongPasswordStaysCounted() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong", false), CLIENT_IP))
                .isInstanceOf(BadCredentialsException.class);
        verify(loginAttempts, never()).giveBack(anyString(), anyString());
        verify(loginAttempts, never()).release(anyString());
    }

    @Test
    void theIdentifierIsLookedUpWithoutSurroundingWhitespace() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        // An em space, which String.trim() would keep.
        assertThatThrownBy(() -> service.login(new LoginRequest("\u2003Alice ", "wrong", false), CLIENT_IP))
                .isInstanceOf(BadCredentialsException.class);
        verify(users).findByUsernameOrEmail("Alice", "alice");
    }

    @Test
    void anAccessTokenMintedAfterTheSessionsEndedIsNotHandedOut() {
        RefreshTokenService.IssuedRefreshToken successor =
                new RefreshTokenService.IssuedRefreshToken("refresh", Duration.ofDays(1), false, UUID.randomUUID());
        when(refreshTokens.rotate("refresh")).thenReturn(new RefreshTokenService.Rotation(42L, 1, successor));
        User alice = new User("alice", "alice@example.com", "hash", Role.USER);
        ReflectionTestUtils.setField(alice, "id", 42L);
        when(users.findById(42L)).thenReturn(Optional.of(alice));
        // A password change committed while the token was minted: its revocation cut-off came too early to catch it.
        when(refreshTokens.isCurrent(42L, 1)).thenReturn(false);

        assertThatThrownBy(() -> service.refresh("refresh")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void failedUserLookupIsAServerErrorAndGivesTheAttemptBack() {
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("no free connection");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new InternalAuthenticationServiceException("lookup failed", outage));

        // The cause, not a 401: GlobalExceptionHandler answers it 500.
        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "secret", false), CLIENT_IP))
                .isSameAs(outage);
        // An unknown identifier may be a mistyped password, so only its hash is a Redis key.
        verify(loginAttempts).giveBack(IP_KEY, "account:" + SecureTokens.hash("alice"));
    }

    @Test
    void aSuspendedUserIsRefusedAfterTheRightPassword() {
        AppUserDetails details = new AppUserDetails(7L, "alice", "hash", Role.USER, true, 0, true);
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));

        assertSuspended(() -> service.login(new LoginRequest("alice", "secret", false), CLIENT_IP));
        verify(audit).record(AuditEvent.LOGIN_SUSPENDED, 7L, "method", "password");
    }

    @Test
    void aUserSuspendedDuringTheSecondFactorIsRefused() {
        when(challenges.find("mfa-token")).thenReturn(Optional.of(new MfaChallengeService.Challenge(42, 1, false)));
        when(mfa.verifyLogin(42, "123456")).thenReturn(true);
        when(challenges.consume("mfa-token")).thenReturn(true);
        when(users.findById(42L)).thenReturn(Optional.of(suspendedUser(42L)));

        assertSuspended(() -> service.loginWithMfa("mfa-token", "123456", CLIENT_IP));
        verify(audit).record(AuditEvent.LOGIN_SUSPENDED, 42L, "method", "mfa");
    }

    @Test
    void aSuspendedUserCannotLogInWithGitHub() {
        ClientRegistration github = mock(ClientRegistration.class);
        SocialProfile profile = new SocialProfile("github", "1", "alice@example.com", true, "alice");
        when(socialProviders.find("github")).thenReturn(Optional.of(github));
        when(socialProfiles.fetch(github, "code", "verifier")).thenReturn(profile);
        when(socialAccounts.userIdFor(profile)).thenReturn(42L);
        when(users.findById(42L)).thenReturn(Optional.of(suspendedUser(42L)));

        assertSuspended(() -> service.socialLogin("github", "code", "verifier"));
        verify(audit).record(AuditEvent.LOGIN_SUSPENDED, 42L, "method", "github");
    }

    private static User suspendedUser(long id) {
        User user = new User("alice", "alice@example.com", "hash", Role.USER);
        ReflectionTestUtils.setField(user, "id", id);
        user.setSuspendedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    private static void assertSuspended(ThrowingCallable login) {
        assertThatThrownBy(login)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType()).hasToString("urn:problem:account-suspended"));
    }

    @Test
    void mfaCodeThatCannotBeCheckedGivesTheAttemptBack() {
        when(challenges.find("mfa-token")).thenReturn(Optional.of(new MfaChallengeService.Challenge(42, 1, false)));
        when(mfa.verifyLogin(42, "123456")).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> service.loginWithMfa("mfa-token", "123456", CLIENT_IP))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(loginAttempts).giveBack(IP_KEY, "account:42");
        verify(loginAttempts).release("mfa:42");
    }

    @Test
    void aBlockByTheCodeLimitDoesNotSpendTheIpAndAccountAttempts() {
        // Otherwise guessing codes past their own limit would also lock the account's password logins.
        when(challenges.find("mfa-token")).thenReturn(Optional.of(new MfaChallengeService.Challenge(42, 1, false)));
        when(loginAttempts.tryAcquire(eq("mfa:42"), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> service.loginWithMfa("mfa-token", "123456", CLIENT_IP))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType()).hasToString("urn:problem:login-locked"));
        verify(loginAttempts).giveBack(IP_KEY, "account:42");
        verify(mfa, never()).verifyLogin(anyLong(), anyString());
    }
}
