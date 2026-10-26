package com.hikmethankolay.template;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    protected static String uniqueUsername() {
        return "user_" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String json(String template, Object... args) {
        return template.formatted(args);
    }

    /** Registers a user whose email is {@code <username>@example.com} and returns its id. */
    protected long register(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s","email":"%s@example.com","password":"%s"}""", username, username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
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
