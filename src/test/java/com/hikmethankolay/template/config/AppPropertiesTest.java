package com.hikmethankolay.template.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.config.AppProperties.Jwt.Algorithm;
import com.hikmethankolay.template.config.AppProperties.SocialLogin.Client;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The validation methods of the {@link AppProperties} records, called directly. */
class AppPropertiesTest {

    private static final String VARIED = "k3Jd9-Qw7Lz2Xp0Vb8Nm4Rt6Ys1Hc5Gf";

    @Test
    void jwtNeedsTheKeysOfTheSelectedAlgorithm() {
        assertThat(jwt(Algorithm.HS256, List.of(VARIED), null).isKeyConfigured())
                .isTrue();
        assertThat(jwt(Algorithm.HS256, null, List.of("pem")).isKeyConfigured()).isFalse();
        assertThat(jwt(Algorithm.HS256, List.of(), null).isKeyConfigured()).isFalse();
        assertThat(jwt(Algorithm.RS256, null, List.of("pem")).isKeyConfigured()).isTrue();
        assertThat(jwt(Algorithm.RS256, List.of(VARIED), null).isKeyConfigured())
                .isFalse();
    }

    @Test
    void jwtSecretsNeed43Characters() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String base64Of32Bytes = "ZKFiM1vT8x4QeW2nLrJ0aHs9uYgDcB6pX7oKjN5tE3I";

        // 32 Base64 characters carry 192 bits: refused with a message that says how to make one.
        assertThat(validator.validate(jwt(Algorithm.HS256, List.of(VARIED), null)))
                .singleElement()
                .satisfies(violation ->
                        assertThat(violation.getMessage()).contains("43").contains("openssl rand -base64 48"));
        assertThat(validator.validate(jwt(Algorithm.HS256, List.of(base64Of32Bytes), null)))
                .isEmpty();
    }

    @Test
    void jwtSecretsMustNotBeRepeatedText() {
        assertThat(jwt(Algorithm.RS256, null, List.of("pem")).isSecretVaried()).isTrue();
        assertThat(jwt(Algorithm.HS256, List.of(VARIED), null).isSecretVaried()).isTrue();
        assertThat(jwt(Algorithm.HS256, List.of(VARIED, "a".repeat(40)), null).isSecretVaried())
                .isFalse();
    }

    @Test
    void secureCookiesGetTheHostAndSecurePrefixes() {
        assertThat(new AppProperties.Cookie("auth_token", "refresh_token", true, "Strict").accessCookieName())
                .isEqualTo("__Host-auth_token");
        assertThat(new AppProperties.Cookie("auth_token", "refresh_token", true, "Strict").refreshCookieName())
                .isEqualTo("__Secure-refresh_token");
        assertThat(new AppProperties.Cookie("auth_token", "refresh_token", false, "Strict").accessCookieName())
                .isEqualTo("auth_token");
        assertThat(new AppProperties.Cookie("auth_token", "refresh_token", false, "Strict").refreshCookieName())
                .isEqualTo("refresh_token");
    }

    @Test
    void aMissingRouteListMeansNoRouteLimits() {
        assertThat(new AppProperties.RateLimit(true, 120, null).routes()).isEmpty();
    }

    @Test
    void adminIsConfiguredOnlyWithEveryField() {
        assertThat(new AppProperties.Admin("owner", "owner@acme.test", "pw").isConfigured())
                .isTrue();
        assertThat(new AppProperties.Admin(" ", "owner@acme.test", "pw").isConfigured())
                .isFalse();
        assertThat(new AppProperties.Admin("owner", null, "pw").isConfigured()).isFalse();
        assertThat(new AppProperties.Admin("owner", "owner@acme.test", "").isConfigured())
                .isFalse();
    }

    @Test
    void mfaKeyMustNotBeRepeatedText() {
        Duration ttl = Duration.ofMinutes(5);

        assertThat(new AppProperties.Mfa(null, ttl).isEncryptionKeyVaried()).isTrue();
        assertThat(new AppProperties.Mfa(VARIED, ttl).isEncryptionKeyVaried()).isTrue();
        assertThat(new AppProperties.Mfa("changeme".repeat(5), ttl).isEncryptionKeyVaried())
                .isFalse();
    }

    @Test
    void providerIsConfiguredOnlyWithIdAndSecret() {
        assertThat(new Client("id", "secret").isConfigured()).isTrue();
        assertThat(new Client("id", null).isConfigured()).isFalse();
        assertThat(new Client(null, "secret").isConfigured()).isFalse();
    }

    @Test
    void redirectUriIsRequiredOnlyWhenAProviderIsConfigured() {
        Client none = new Client(null, null);
        Client configured = new Client("id", "secret");

        assertThat(new AppProperties.SocialLogin(null, none, none).isRedirectUriConfigured())
                .isTrue();
        assertThat(new AppProperties.SocialLogin("https://app.acme.test/cb", configured, configured)
                        .isRedirectUriConfigured())
                .isTrue();
        assertThat(new AppProperties.SocialLogin(null, configured, none).isRedirectUriConfigured())
                .isFalse();
        assertThat(new AppProperties.SocialLogin(" ", none, configured).isRedirectUriConfigured())
                .isFalse();
    }

    private static AppProperties.Jwt jwt(Algorithm algorithm, List<String> secret, List<String> privateKey) {
        return new AppProperties.Jwt(
                algorithm,
                secret,
                privateKey,
                "issuer",
                "audience",
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofDays(7),
                Duration.ofDays(90));
    }
}
