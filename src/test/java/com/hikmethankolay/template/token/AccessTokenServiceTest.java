package com.hikmethankolay.template.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.token.dto.CreateAccessTokenRequest;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.SessionsInvalidated;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The deployment settings that limit what a token may do, and the checks behind each request. */
@ExtendWith(OutputCaptureExtension.class)
class AccessTokenServiceTest {

    private static final String VALUE = "pat_value";

    private final AccessTokenRepository tokens = mock(AccessTokenRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    private final UserService userService = mock(UserService.class);

    private final AuditLog audit = mock(AuditLog.class);

    @Test
    void theRoleIsCappedAtTheConfiguredMaximum() {
        AccessTokenService capped = service(Role.USER, true);
        AccessTokenService uncapped = service(Role.OWNER, true);

        assertThat(capped.effectiveRole(Role.OWNER)).isEqualTo(Role.USER);
        assertThat(capped.effectiveRole(Role.ADMIN)).isEqualTo(Role.USER);
        assertThat(service(Role.ADMIN, true).effectiveRole(Role.OWNER)).isEqualTo(Role.ADMIN);
        assertThat(uncapped.effectiveRole(Role.OWNER)).isEqualTo(Role.OWNER);
        assertThat(uncapped.effectiveRole(Role.USER)).isEqualTo(Role.USER);
    }

    @Test
    void writeTokensBecomeReadOnlyWhenWritesAreDisabled() {
        assertThat(service(Role.OWNER, false).effectiveScope(AccessTokenScope.WRITE))
                .isEqualTo(AccessTokenScope.READ);
        assertThat(service(Role.OWNER, true).effectiveScope(AccessTokenScope.WRITE))
                .isEqualTo(AccessTokenScope.WRITE);
    }

    @Test
    void disabledTokensAuthenticateNothingAndCannotBeCreated() {
        AccessTokenService disabled =
                service(new AccessTokenProperties(false, Role.OWNER, true, Duration.ofDays(365), 20));

        assertThat(disabled.authenticate(VALUE)).isEmpty();
        assertThatThrownBy(() -> disabled.create(1L, request()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType().toString())
                                .isEqualTo("urn:problem:access-tokens-disabled"));
        verifyNoInteractions(tokens, userService);
    }

    @Test
    void aUserAtTheLimitCannotCreateAnother() {
        when(tokens.countByUserIdAndExpiresAtAfter(anyLong(), any())).thenReturn(20L);

        assertThatThrownBy(() -> service(Role.OWNER, true).create(1L, request()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType().toString())
                                .isEqualTo("urn:problem:access-token-limit-reached"));
        verify(tokens, never()).save(any());
    }

    @Test
    void anExpiredTokenIsRejectedWithoutLoadingItsUser() {
        when(tokens.findByTokenHash(SecureTokens.hash(VALUE)))
                .thenReturn(Optional.of(token(Instant.now().minusSeconds(1))));

        assertThat(service(Role.OWNER, true).authenticate(VALUE)).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void aSuspendedUsersTokenIsRejected() {
        when(tokens.findByTokenHash(SecureTokens.hash(VALUE)))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(60))));
        User suspended = new User("alice", "alice@example.com", "hash", Role.USER);
        suspended.setSuspendedAt(Instant.now());
        when(users.findById(1L)).thenReturn(Optional.of(suspended));

        assertThat(service(Role.OWNER, true).authenticate(VALUE)).isEmpty();
        verify(tokens, never()).touch(anyLong(), any(), any());
    }

    @Test
    void endingTheSessionsOfAUserWithoutTokensRecordsNothing() {
        when(tokens.deleteAllByUserId(1L)).thenReturn(0);

        service(Role.OWNER, true).revokeAll(new SessionsInvalidated(1L));

        verifyNoInteractions(audit);
    }

    @Test
    void theCleanupDeletesExpiredTokensAndLogsOnlyWhenItDid(CapturedOutput output) {
        when(tokens.deleteExpired(any())).thenReturn(0, 3);
        AccessTokenService service = service(Role.OWNER, true);

        service.deleteExpiredTokens();
        assertThat(output).doesNotContain("expired access tokens");
        service.deleteExpiredTokens();

        assertThat(output).contains("Deleted 3 expired access tokens");
        verify(tokens, org.mockito.Mockito.times(2)).deleteExpired(any());
    }

    @Test
    void onlyPrefixedValuesAreAccessTokens() {
        assertThat(AccessTokenService.isAccessToken("pat_abc")).isTrue();
        assertThat(AccessTokenService.isAccessToken("eyJhbGciOi")).isFalse();
        assertThat(AccessTokenService.isAccessToken((String) null)).isFalse();
    }

    private static AccessToken token(Instant expiresAt) {
        return new AccessToken(1L, "script", SecureTokens.hash(VALUE), AccessTokenScope.READ, expiresAt);
    }

    private static CreateAccessTokenRequest request() {
        return new CreateAccessTokenRequest("script", AccessTokenScope.READ, 30, "password");
    }

    private AccessTokenService service(Role maxRole, boolean allowWrite) {
        return service(new AccessTokenProperties(true, maxRole, allowWrite, Duration.ofDays(365), 20));
    }

    private AccessTokenService service(AccessTokenProperties properties) {
        return new AccessTokenService(tokens, users, userService, audit, mock(SecurityMailer.class), properties);
    }
}
