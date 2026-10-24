package com.hikmethankolay.template.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/** The real HTTP exchange, against a local stand-in for GitHub and Google. */
@ExtendWith(OutputCaptureExtension.class)
class SocialProfileClientTest {

    private final SocialProfileClient client = new SocialProfileClient();

    private final AtomicReference<String> tokenRequest = new AtomicReference<>();

    private HttpServer server;

    private String base;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"access_token":"provider-token","token_type":"bearer"}""");
        });
        server.createContext("/bad-token", exchange -> respond(exchange, 400, """
                {"error":"invalid_grant","error_description":"The code is wrong.\\r\\nForged line"}"""));
        server.createContext("/user", exchange -> respond(exchange, 200, """
                {"id":42,"login":"octocat","email":null}"""));
        server.createContext("/user/emails", exchange -> respond(exchange, 200, """
                [{"email":"old@example.com","primary":false,"verified":true},
                 {"email":"octo@example.com","primary":true,"verified":true}]"""));
        server.createContext("/userinfo", exchange -> respond(exchange, 200, """
                {"sub":"g-1","email":"g@example.com","email_verified":true}"""));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
    }

    @Test
    void githubProfileHasThePrimaryEmailAndThePkceVerifierIsSent() {
        String verifier = "v".repeat(43);

        SocialProfile profile =
                client.fetch(registration(CommonOAuth2Provider.GITHUB, "github", "/token", "/user"), "c-1", verifier);

        assertThat(profile).isEqualTo(new SocialProfile("github", "42", "octo@example.com", true, "octocat"));
        assertThat(tokenRequest.get())
                .contains("grant_type=authorization_code", "code=c-1", "code_verifier=" + verifier);
    }

    @Test
    void googleProfileComesFromUserInfo() {
        SocialProfile profile =
                client.fetch(registration(CommonOAuth2Provider.GOOGLE, "google", "/token", "/userinfo"), "c-2", null);

        assertThat(profile).isEqualTo(new SocialProfile("google", "g-1", "g@example.com", true, null));
        assertThat(tokenRequest.get()).doesNotContain("code_verifier");
    }

    @Test
    void aRejectedCodeIsBadCredentialsAndLoggedOnOneLine(CapturedOutput output) {
        ClientRegistration registration = registration(CommonOAuth2Provider.GITHUB, "github", "/bad-token", "/user");

        assertThatThrownBy(() -> client.fetch(registration, "c-3", null)).isInstanceOf(BadCredentialsException.class);
        // The provider's line break would otherwise forge a log line.
        assertThat(output).contains("Forged line").doesNotContain("\nForged line");
    }

    private ClientRegistration registration(CommonOAuth2Provider provider, String id, String token, String user) {
        return provider.getBuilder(id)
                .clientId("client")
                .clientSecret("secret")
                .redirectUri("http://localhost:3000/oauth2/callback")
                .tokenUri(base + token)
                .userInfoUri(base + user)
                .build();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
