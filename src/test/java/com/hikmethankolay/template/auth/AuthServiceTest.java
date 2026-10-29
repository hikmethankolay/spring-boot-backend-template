package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.account.AccountService;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.auth.dto.LoginRequest;
import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.mfa.MfaService;
import com.hikmethankolay.template.security.LoginAttemptService;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.social.SocialAccountService;
import com.hikmethankolay.template.social.SocialLoginProviders;
import com.hikmethankolay.template.social.SocialProfileClient;
import com.hikmethankolay.template.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Unit tests of how {@link AuthService} counts failed logins: wrong credentials count, server errors do not. */
class AuthServiceTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String IP_KEY = "ip:" + ClientIp.limitKey(CLIENT_IP);

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MfaService mfa = mock(MfaService.class);
    private final MfaChallengeService challenges = mock(MfaChallengeService.class);
    private final AuthService service = new AuthService(
            authenticationManager,
            loginAttempts,
            mock(TokenService.class),
            mock(AccountService.class),
            users,
            mock(RefreshTokenService.class),
            mock(JwtDecoder.class),
            TestProperties.create(),
            mock(AuditLog.class),
            mfa,
            challenges,
            mock(SocialLoginProviders.class),
            mock(SocialProfileClient.class),
            mock(SocialAccountService.class),
            mock(KnownDevices.class));

    @BeforeEach
    void allowAttempts() {
        when(loginAttempts.tryAcquire(anyString(), anyString())).thenReturn(true);
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
    void failedUserLookupIsAServerErrorAndGivesTheAttemptBack() {
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("no free connection");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new InternalAuthenticationServiceException("lookup failed", outage));

        // The cause, not a 401: GlobalExceptionHandler answers it 500.
        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "secret", false), CLIENT_IP))
                .isSameAs(outage);
        verify(loginAttempts).giveBack(IP_KEY, "account:alice");
    }

    @Test
    void mfaCodeThatCannotBeCheckedGivesTheAttemptBack() {
        when(challenges.find("mfa-token")).thenReturn(Optional.of(new MfaChallengeService.Challenge(42, 1, false)));
        when(mfa.verifyLogin(42, "123456")).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> service.loginWithMfa("mfa-token", "123456", CLIENT_IP))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(loginAttempts).giveBack(IP_KEY, "account:42");
    }
}
