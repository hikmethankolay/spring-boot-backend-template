package com.hikmethankolay.template.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hikmethankolay.template.TestProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

class ProductionSettingsCheckTest {

    private static final String FRONTEND = "https://app.example.com";
    private static final String SMTP_HOST = "smtp.acme.test";

    @Test
    void acceptsRealSettings() {
        assertThat(ProductionSettingsCheck.problems(
                        properties(FRONTEND, FRONTEND, FRONTEND + "/cb", true), "s3cret", SMTP_HOST, 587))
                .isEmpty();
    }

    @Test
    void namesEveryDevelopmentDefault() {
        String local = "http://localhost:3000";

        List<String> problems = ProductionSettingsCheck.problems(
                withFrom(properties(local, local, local + "/oauth2/callback", true), "no-reply@example.com"),
                "app",
                "localhost",
                1025);

        assertThat(problems).hasSize(6);
        assertThat(String.join("\n", problems))
                .contains(
                        "CORS_ALLOWED_ORIGINS",
                        "MAIL_LINK_BASE_URL",
                        "OAUTH2_REDIRECT_URI",
                        "DB_PASSWORD",
                        "MAIL_HOST",
                        "MAIL_FROM");
    }

    @Test
    void oneLocalOriginAmongRealOnesIsAProblem() {
        AppProperties p = properties(FRONTEND, FRONTEND, null, false);
        AppProperties mixed = new AppProperties(
                p.jwt(),
                p.cookie(),
                new AppProperties.Cors(List.of(FRONTEND, "http://127.0.0.1:5173")),
                p.login(),
                p.rateLimit(),
                p.account(),
                p.mail(),
                p.admin(),
                p.mfa(),
                p.socialLogin());

        assertThat(ProductionSettingsCheck.problems(mixed, "s3cret", SMTP_HOST, 587))
                .singleElement()
                .asString()
                .contains("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void aLocalRedirectUriIsFineWithoutSocialLogin() {
        assertThat(ProductionSettingsCheck.problems(
                        properties(FRONTEND, FRONTEND, "http://localhost:3000/oauth2/callback", false),
                        "s3cret",
                        SMTP_HOST,
                        587))
                .isEmpty();
    }

    @Test
    void aLocalRelayOnAnotherPortIsFine() {
        assertThat(ProductionSettingsCheck.problems(
                        properties(FRONTEND, FRONTEND, null, false), "s3cret", "localhost", 25))
                .isEmpty();
    }

    @Test
    void missingOriginsAndMailPortAreNotProblems() {
        AppProperties p = properties(FRONTEND, FRONTEND, null, false);
        AppProperties noOrigins = new AppProperties(
                p.jwt(),
                p.cookie(),
                new AppProperties.Cors(null),
                p.login(),
                p.rateLimit(),
                p.account(),
                p.mail(),
                p.admin(),
                p.mfa(),
                p.socialLogin());

        assertThat(ProductionSettingsCheck.problems(noOrigins, "s3cret", "localhost", null))
                .isEmpty();
    }

    @Test
    void mailpitOnIpv6LoopbackIsAProblem() {
        assertThat(ProductionSettingsCheck.problems(properties(FRONTEND, FRONTEND, null, false), "s3cret", "::1", 1025))
                .singleElement()
                .asString()
                .contains("MAIL_HOST");
    }

    @Test
    void googleLoginAloneNeedsARealRedirectUri() {
        AppProperties p = properties(FRONTEND, FRONTEND, null, false);
        AppProperties.SocialLogin.Client none = new AppProperties.SocialLogin.Client(null, null);
        AppProperties google = new AppProperties(
                p.jwt(),
                p.cookie(),
                p.cors(),
                p.login(),
                p.rateLimit(),
                p.account(),
                p.mail(),
                p.admin(),
                p.mfa(),
                new AppProperties.SocialLogin(
                        "http://localhost:3000/cb", none, new AppProperties.SocialLogin.Client("id", "secret")));

        assertThat(ProductionSettingsCheck.problems(google, "s3cret", SMTP_HOST, 587))
                .singleElement()
                .asString()
                .contains("OAUTH2_REDIRECT_URI");
    }

    @Test
    void recognisesPlaceholderAddresses() {
        assertThat(List.of("no-reply@example.com", "a@EXAMPLE.org", "a@mail.example.net", " a@example.com "))
                .allMatch(ProductionSettingsCheck::isPlaceholderAddress);
        assertThat(List.of("no-reply@acme.dev", "a@myexample.com", "a@example.com.tr"))
                .noneMatch(ProductionSettingsCheck::isPlaceholderAddress);
        assertThat(ProductionSettingsCheck.isPlaceholderAddress(null)).isFalse();
    }

    @Test
    void recognisesLocalHosts() {
        assertThat(List.of(
                        "http://localhost:3000",
                        "http://LOCALHOST",
                        "http://app.localhost:8080",
                        "http://127.0.0.1",
                        "http://[::1]:3000",
                        "http://0.0.0.0:3000",
                        " http://localhost "))
                .allMatch(ProductionSettingsCheck::isLocal);
        assertThat(List.of(
                        "https://app.example.com",
                        "https://localhost.example.com",
                        "https://my-localhost.dev",
                        "not a url",
                        "*"))
                .noneMatch(ProductionSettingsCheck::isLocal);
        assertThat(ProductionSettingsCheck.isLocal(null)).isFalse();
    }

    @Test
    void refusesToStartWithDevelopmentSettings() {
        AppProperties local = TestProperties.create();
        JdbcConnectionDetails database = database("app");

        assertThatThrownBy(() -> new ProductionSettingsCheck(local, database, "localhost", 1025))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod profile")
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void startsWithRealSettings() {
        AppProperties real = properties(FRONTEND, FRONTEND, null, false);

        new ProductionSettingsCheck(real, database("s3cret"), SMTP_HOST, 587);
    }

    private static AppProperties properties(String origin, String linkBaseUrl, String redirectUri, boolean github) {
        AppProperties p = TestProperties.create();
        AppProperties.SocialLogin.Client none = new AppProperties.SocialLogin.Client(null, null);
        return new AppProperties(
                p.jwt(),
                p.cookie(),
                new AppProperties.Cors(List.of(origin)),
                p.login(),
                p.rateLimit(),
                p.account(),
                new AppProperties.Mail("no-reply@acme.test", linkBaseUrl, 10, Duration.ofSeconds(30)),
                p.admin(),
                p.mfa(),
                new AppProperties.SocialLogin(
                        redirectUri, github ? new AppProperties.SocialLogin.Client("id", "secret") : none, none));
    }

    private static AppProperties withFrom(AppProperties p, String from) {
        AppProperties.Mail m = p.mail();
        return new AppProperties(
                p.jwt(),
                p.cookie(),
                p.cors(),
                p.login(),
                p.rateLimit(),
                p.account(),
                new AppProperties.Mail(from, m.linkBaseUrl(), m.maxAttempts(), m.retryDelay()),
                p.admin(),
                p.mfa(),
                p.socialLogin());
    }

    private static JdbcConnectionDetails database(String password) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return "app";
            }

            @Override
            public String getPassword() {
                return password;
            }

            @Override
            public String getJdbcUrl() {
                return "jdbc:postgresql://db:5432/app";
            }
        };
    }
}
