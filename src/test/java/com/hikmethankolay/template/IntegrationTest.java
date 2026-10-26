package com.hikmethankolay.template;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for end-to-end tests: full application context, real PostgreSQL via Testcontainers.
 * Every response is checked against {@code api/openapi.json} ({@link OpenApiConformance}).
 * Fails when no Docker-compatible runtime is available, so a green build means every test ran;
 * {@code scripts/test.sh --unit} runs the unit tests alone, without containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class, OpenApiConformance.Configuration.class})
@Testcontainers
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Secret#123-passphrase";
    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "Admin#12345-passphrase";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private CapturingMailSender signUpMail;

    protected static String uniqueUsername() {
        return "user_" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String json(String template, Object... args) {
        return template.formatted(args);
    }

    /** Signs a user up whose email is {@code <username>@example.com} and returns its id. */
    protected long register(String username) throws Exception {
        String body = completeSignUp(username + "@example.com", username, PASSWORD)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Starts a sign-up for an address and finishes it with the token from the emailed link. */
    protected ResultActions completeSignUp(String address, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", address)))
                .andExpect(status().isAccepted());
        return mockMvc.perform(post("/api/v1/auth/register/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"token":"%s","username":"%s","password":"%s"}""", signUpToken(address), username, password)));
    }

    /** Returns the token from the newest sign-up link sent to an address. */
    protected String signUpToken(String address) {
        return CapturingMailSender.token(signUpMail
                .latest(address, "Finish creating your account")
                .orElseThrow(() -> new AssertionError("No sign-up email for " + address)));
    }

    /** Opens the newest "Confirm your email" link sent to an address. */
    protected void confirmEmail(String address) throws Exception {
        String token = CapturingMailSender.token(signUpMail
                .latest(address, "Confirm your email")
                .orElseThrow(() -> new AssertionError("No verification email for " + address)));
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s"}""", token)))
                .andExpect(status().isNoContent());
    }

    protected String login(String identifier, String password) throws Exception {
        return loginTokens(identifier, password).accessToken();
    }

    /** Logs in and returns both tokens. */
    protected Tokens loginTokens(String identifier, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", identifier, password)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    /** Access and refresh token from a login or refresh response. */
    public record Tokens(String accessToken, String refreshToken) {}

    protected String registerAndLogin(String username) throws Exception {
        register(username);
        return login(username, PASSWORD);
    }

    protected static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    /** Login attempts are rate limited per IP, so tests that fail logins use their own address. */
    protected static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
