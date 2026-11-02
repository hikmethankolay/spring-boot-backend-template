package com.hikmethankolay.template.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Which requests an access token may make; JWT logins are never restricted by it. */
class AccessTokenPolicyTest {

    private final AccessTokenPolicy policy = new AccessTokenPolicy();

    @Test
    void anonymousRequestsAreDenied() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(granted(anonymous, "GET", "/api/v1/notes")).isFalse();
    }

    @Test
    void aLoginIsNotRestricted() {
        Authentication login = new JwtAuthenticationToken(jwt(false, "WRITE"), List.of());
        Authentication other = new TestingAuthenticationToken("user", "password", "ROLE_USER");

        assertThat(granted(login, "PUT", "/api/v1/users/me/password")).isTrue();
        assertThat(granted(other, "DELETE", "/api/v1/users/me/sessions")).isTrue();
    }

    @Test
    void aTokenIsKeptOutOfAccountSelfServiceButMayReadItsOwnProfile() {
        Authentication token = new JwtAuthenticationToken(jwt(true, "WRITE"), List.of());

        assertThat(granted(token, "GET", "/api/v1/users/me")).isTrue();
        assertThat(granted(token, "PATCH", "/api/v1/users/me")).isFalse();
        assertThat(granted(token, "GET", "/api/v1/users/me/sessions")).isFalse();
        assertThat(granted(token, "POST", "/api/v1/users/me/access-tokens")).isFalse();
        assertThat(granted(token, "GET", "/api/v1/users/42")).isTrue();
    }

    @Test
    void aReadTokenMayOnlyUseSafeMethods() {
        Authentication token = new JwtAuthenticationToken(jwt(true, "READ"), List.of());

        assertThat(granted(token, "GET", "/api/v1/notes")).isTrue();
        assertThat(granted(token, "HEAD", "/api/v1/notes")).isTrue();
        assertThat(granted(token, "POST", "/api/v1/notes")).isFalse();
        assertThat(granted(token, "DELETE", "/api/v1/notes/1")).isFalse();
    }

    private boolean granted(Authentication authentication, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return policy.authorize(() -> authentication, new RequestAuthorizationContext(request))
                .isGranted();
    }

    private static Jwt jwt(boolean accessToken, String scope) {
        Jwt.Builder builder = Jwt.withTokenValue("value").header("alg", "none").subject("1");
        if (accessToken) {
            builder.claim(AccessTokenService.TOKEN_ID_CLAIM, 7L).claim(AccessTokenService.SCOPE_CLAIM, scope);
        } else {
            builder.claim("sid", "session");
        }
        return builder.build();
    }
}
