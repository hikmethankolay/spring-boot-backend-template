package com.hikmethankolay.template.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.TestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionSettingsWarningsTest {

    private static final String VERIFIED_DB = "jdbc:postgresql://db.acme.test:5432/app?sslmode=verify-full";

    @Test
    void hardenedSettingsGiveNoWarning() {
        assertThat(warnings(VERIFIED_DB, "redis.acme.test", "s3cret", true, false, true, false))
                .isEmpty();
    }

    @Test
    void namesEveryWeakenedSetting() {
        // TestProperties has insecure cookies and verified email off; this turns the rate limit off too.
        AppProperties p = TestProperties.create();
        AppProperties weak = new AppProperties(
                p.jwt(),
                p.cookie(),
                p.cors(),
                p.login(),
                new AppProperties.RateLimit(false, 120, List.of()),
                p.account(),
                p.mail(),
                p.admin(),
                p.mfa(),
                p.socialLogin());

        List<String> warnings = ProductionSettingsWarnings.warnings(
                weak, "jdbc:postgresql://db.acme.test:5432/app", "redis.acme.test", "", false, true, false, true);

        assertThat(warnings).hasSize(9);
        assertThat(String.join("\n", warnings))
                .contains(
                        "DB_URL",
                        "REDIS_PASSWORD",
                        "REDIS_SSL_ENABLED",
                        "COOKIE_SECURE",
                        "SWAGGER_ENABLED",
                        "RATE_LIMIT_ENABLED",
                        "REQUIRE_VERIFIED_EMAIL",
                        "MAIL_STARTTLS_REQUIRED",
                        "FLYWAY_ENABLED");
    }

    @Test
    void aRemoteDatabaseNeedsAVerifyingSslMode() {
        assertThat(List.of(
                        "jdbc:postgresql://db.acme.test/app",
                        "jdbc:postgresql://postgres:5432/app",
                        "jdbc:postgresql://db.acme.test/app?sslmode=require",
                        "jdbc:postgresql://db.acme.test/app?sslmode=prefer",
                        "jdbc:postgresql://db.acme.test/app?x=sslmode=verify-full",
                        "jdbc:postgresql://localhost,db.acme.test/app"))
                .allSatisfy(url ->
                        assertThat(dbWarnings(url)).singleElement().asString().contains("DB_URL"));
        assertThat(List.of(
                        VERIFIED_DB,
                        "jdbc:postgresql://db.acme.test/app?ApplicationName=x&SSLMODE=verify-ca",
                        "jdbc:postgresql://localhost:5432/app",
                        "jdbc:postgresql://127.0.0.1,[::1]:5433/app",
                        "jdbc:postgresql:///app",
                        "jdbc:postgresql:app"))
                .allSatisfy(url -> assertThat(dbWarnings(url)).isEmpty());
    }

    @Test
    void localRedisWithoutTlsIsFineButNeedsAPassword() {
        assertThat(warnings(VERIFIED_DB, "localhost", "s3cret", false, false, true, false))
                .isEmpty();
        assertThat(warnings(VERIFIED_DB, "localhost", null, false, false, true, false))
                .singleElement()
                .asString()
                .contains("REDIS_PASSWORD");
    }

    private static List<String> dbWarnings(String jdbcUrl) {
        return warnings(jdbcUrl, "localhost", "s3cret", true, false, true, false);
    }

    /** Warnings with secure cookies, the rate limit on and verified email required, as the prod profile sets them. */
    private static List<String> warnings(
            String jdbcUrl,
            String redisHost,
            String redisPassword,
            boolean redisSsl,
            boolean apiDocs,
            boolean startTlsRequired,
            boolean flyway) {
        AppProperties p = TestProperties.create();
        AppProperties prod = new AppProperties(
                p.jwt(),
                new AppProperties.Cookie("auth_token", "refresh_token", true, "Strict"),
                p.cors(),
                p.login(),
                p.rateLimit(),
                new AppProperties.Account(
                        true,
                        p.account().verificationTokenTtl(),
                        p.account().passwordResetTokenTtl(),
                        p.account().pwnedCheckEnabled()),
                p.mail(),
                p.admin(),
                p.mfa(),
                p.socialLogin());
        return ProductionSettingsWarnings.warnings(
                prod, jdbcUrl, redisHost, redisPassword, redisSsl, apiDocs, startTlsRequired, flyway);
    }
}
