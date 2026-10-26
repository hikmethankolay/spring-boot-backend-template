package com.hikmethankolay.template;

import com.hikmethankolay.template.config.AppProperties;
import java.time.Duration;
import java.util.List;

/** {@link AppProperties} for unit tests that do not start a Spring context. */
public final class TestProperties {

    public static final String JWT_SECRET = "unit-test-secret-that-is-at-least-43-characters";
    public static final String ISSUER = "test-issuer";
    public static final String AUDIENCE = "test-audience";

    private TestProperties() {}

    public static AppProperties create() {
        return withJwt(hs256(List.of(JWT_SECRET), ISSUER));
    }

    /** HS256 settings with the given secrets (the first signs) and issuer. */
    public static AppProperties.Jwt hs256(List<String> secrets, String issuer) {
        return new AppProperties.Jwt(
                AppProperties.Jwt.Algorithm.HS256,
                secrets,
                List.of(),
                issuer,
                AUDIENCE,
                Duration.ofMinutes(15),
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(7),
                Duration.ofDays(90));
    }

    /** Default test properties with different JWT settings. */
    public static AppProperties withJwt(AppProperties.Jwt jwt) {
        return new AppProperties(
                jwt,
                new AppProperties.Cookie("auth_token", "refresh_token", false, "Strict"),
                new AppProperties.Cors(List.of("http://localhost:3000")),
                new AppProperties.Login(3, 5, Duration.ofHours(1)),
                new AppProperties.RateLimit(true, 120, List.of()),
                new AppProperties.Account(false, Duration.ofHours(24), Duration.ofHours(1), false),
                new AppProperties.Mail("no-reply@example.com", "http://localhost:3000", 10, Duration.ofSeconds(30)),
                new AppProperties.Admin(null, null, null),
                new AppProperties.Mfa("unit-test-mfa-key-that-is-at-least-32-chars", Duration.ofMinutes(5)),
                new AppProperties.SocialLogin(
                        null,
                        new AppProperties.SocialLogin.Client(null, null),
                        new AppProperties.SocialLogin.Client(null, null)));
    }
}
