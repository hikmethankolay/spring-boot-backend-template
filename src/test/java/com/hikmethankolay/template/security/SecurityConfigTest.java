package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.user.Role;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.cors.CorsConfiguration;

/** The security beans, called directly: a cached Spring context would hide a broken bean method from PIT. */
class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();
    private final AppProperties properties = TestProperties.create();

    /** CORS allows the configured origins with credentials, the API's methods and headers, and exposes the documented response headers. */
    @Test
    void corsAllowsOnlyTheConfiguredOriginsWithCredentials() {
        CorsConfiguration cors = config.corsConfigurationSource(properties)
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/users/me"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:3000");
        assertThat(cors.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cors.getAllowedHeaders()).containsExactly("Authorization", "Content-Type");
        assertThat(cors.getExposedHeaders()).contains("X-Trace-Id", "Retry-After", "RateLimit-Remaining");
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getMaxAge()).isEqualTo(3600L);
    }

    /** The {@code roles} claim becomes {@code ROLE_*} authorities. */
    @Test
    void rolesClaimBecomesRoleAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("1")
                .claim(TokenService.ROLES_CLAIM, List.of("ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(SecurityConfig.jwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN")
                .doesNotContain("ADMIN", "SCOPE_ADMIN");
    }

    /** The role hierarchy gives the owner every administrator and user right. */
    @Test
    void ownerImpliesAdminWhichImpliesUser() {
        assertThat(SecurityConfig.roleHierarchy()
                        .getReachableGrantedAuthorities(AuthorityUtils.createAuthorityList("ROLE_OWNER")))
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_OWNER", "ROLE_ADMIN", "ROLE_USER");
    }

    /** Passwords are hashed with Argon2id at the OWASP minimum and stored with the algorithm id. */
    @Test
    void passwordsAreHashedWithArgon2idAndTheAlgorithmId() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("Secret#123-passphrase");

        assertThat(hash).startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(encoder.matches("Secret#123-passphrase", hash)).isTrue();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
    }

    /** Hashes from earlier versions still match and are marked for an upgrade. */
    @Test
    void bcryptHashesStillMatchAndNeedAnUpgrade() {
        PasswordEncoder encoder = config.passwordEncoder();
        String legacy = "{bcrypt}" + new BCryptPasswordEncoder().encode("Secret#123-passphrase");

        assertThat(encoder.matches("Secret#123-passphrase", legacy)).isTrue();
        assertThat(encoder.matches("wrong", legacy)).isFalse();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();
    }

    /** A login with a bcrypt hash hands an Argon2id hash of the same password to the upgrade service. */
    @Test
    void loginWithBcryptHashUpgradesIt() {
        PasswordEncoder encoder = config.passwordEncoder();
        AppUserDetails user = new AppUserDetails(
                1L,
                "alice",
                "{bcrypt}" + new BCryptPasswordEncoder().encode("Secret#123-passphrase"),
                Role.USER,
                true,
                0);
        AtomicReference<String> upgraded = new AtomicReference<>();
        AuthenticationManager manager = config.authenticationManager(username -> user, encoder, (details, hash) -> {
            upgraded.set(hash);
            return details;
        });

        manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "Secret#123-passphrase"));

        assertThat(upgraded.get()).startsWith("{argon2}");
        assertThat(encoder.matches("Secret#123-passphrase", upgraded.get())).isTrue();
    }

    /** The authentication manager checks passwords with the configured encoder. */
    @Test
    void authenticationUsesTheGivenPasswordEncoder() {
        // A plain encoder whose hashes have no {id} prefix: the default delegating encoder would reject them.
        PasswordEncoder plain = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return "plain:" + raw;
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return encoded.equals(encode(raw));
            }
        };
        AppUserDetails user = new AppUserDetails(1L, "alice", "plain:Secret#123-passphrase", Role.USER, true, 0);
        AuthenticationManager manager =
                config.authenticationManager(username -> user, plain, (details, hash) -> details);

        assertThat(manager.authenticate(
                                UsernamePasswordAuthenticationToken.unauthenticated("alice", "Secret#123-passphrase"))
                        .getPrincipal())
                .isEqualTo(user);
        assertThatThrownBy(() ->
                        manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    /** The access token is read from the auth cookie when there is no header. */
    @Test
    void bearerTokenIsReadFromTheAuthCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.setCookies(new Cookie("auth_token", "abc"));

        assertThat(config.bearerTokenResolver(properties).resolve(request)).isEqualTo("abc");
    }

    /** With HS256 the JWK Set endpoint publishes no keys. */
    @Test
    void hs256KeysPublishAnEmptyJwkSet() {
        JwtKeys keys = config.jwtKeys(properties);

        assertThat(new JwksController(keys).jwks()).isEqualTo(Map.of("keys", List.of()));
    }
}
