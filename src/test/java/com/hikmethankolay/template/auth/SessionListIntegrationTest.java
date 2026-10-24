package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Users see their sessions and can end any one of them, which logs that device out at once. */
class SessionListIntegrationTest extends IntegrationTest {

    private static final String SESSIONS = "/api/v1/users/me/sessions";

    @Autowired
    private RefreshTokenService refreshTokens;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void listShowsEverySessionWithItsClientAndMarksTheCurrentOne() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens laptop = loginTokens(username, PASSWORD);
        loginFromPhone(username);

        List<Map<String, Object>> sessions = sessions(laptop.accessToken());

        assertThat(sessions).hasSize(2);
        assertThat(sessions).filteredOn(s -> (Boolean) s.get("current")).hasSize(1);
        assertThat(sessions)
                .filteredOn(s -> !(Boolean) s.get("current"))
                .singleElement()
                .satisfies(phone -> {
                    assertThat(phone).containsEntry("userAgent", "Phone/1.0");
                    assertThat(phone).containsEntry("ipAddress", "198.51.100.20");
                    assertThat(phone).containsKeys("id", "startedAt", "lastUsedAt", "expiresAt", "rememberMe");
                });
    }

    @Test
    void endingAnotherSessionLogsThatDeviceOutAndKeepsThisOne() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens laptop = loginTokens(username, PASSWORD);
        Tokens phone = loginFromPhone(username);
        String phoneSession = sessions(laptop.accessToken()).stream()
                .filter(s -> !(Boolean) s.get("current"))
                .map(s -> (String) s.get("id"))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(delete(SESSIONS + "/" + phoneSession).with(bearer(laptop.accessToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(phone.accessToken())))
                .andExpect(status().isUnauthorized());
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me").with(bearer(laptop.accessToken())))
                .andExpect(status().isOk());
        assertThat(sessions(laptop.accessToken()))
                .singleElement()
                .satisfies(s -> assertThat(s).containsEntry("current", true));
    }

    @Test
    void refreshingKeepsTheSessionAndWhenItStarted() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);
        Map<String, Object> before = sessions(login.accessToken()).getFirst();

        String body = refresh(login.refreshToken())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> after =
                sessions(JsonPath.read(body, "$.accessToken")).getFirst();
        assertThat(after)
                .containsEntry("id", before.get("id"))
                .containsEntry("startedAt", before.get("startedAt"))
                .containsEntry("current", true);
    }

    @Test
    void anotherUsersSessionOrAnUnknownOneIsNotFound() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        Tokens ownerSession = loginTokens(owner, PASSWORD);
        String ownerSessionId =
                (String) sessions(ownerSession.accessToken()).getFirst().get("id");
        String intruder = registerAndLogin(uniqueUsername());

        mockMvc.perform(delete(SESSIONS + "/" + ownerSessionId).with(bearer(intruder)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:session-not-found"));
        mockMvc.perform(delete(SESSIONS + "/" + UUID.randomUUID()).with(bearer(intruder)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(ownerSession.accessToken())))
                .andExpect(status().isOk());
    }

    /** Ending a session while it is being refreshed also ends the token that refresh issues. */
    @Test
    void endingASessionDuringARefreshAlsoEndsTheNewToken() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        Tokens login = loginTokens(username, PASSWORD);
        UUID session = UUID.fromString(
                (String) sessions(login.accessToken()).getFirst().get("id"));
        CountDownLatch rotated = new CountDownLatch(1);

        // The refresh keeps its transaction open after issuing the successor, like a slow commit.
        CompletableFuture<String> refresh = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            String successor =
                    refreshTokens.rotate(login.refreshToken()).successor().value();
            rotated.countDown();
            pause(Duration.ofMillis(500));
            return successor;
        }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> revoke = CompletableFuture.runAsync(() -> refreshTokens.revoke(userId, session));

        String successor = refresh.get(10, TimeUnit.SECONDS);
        revoke.get(10, TimeUnit.SECONDS);
        refresh(successor).andExpect(status().isUnauthorized());
    }

    /** Two refreshes with one token cannot both succeed: the second waits for the first and counts as reuse. */
    @Test
    void aSecondRefreshWithTheSameTokenDuringTheFirstEndsTheSession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);
        CountDownLatch rotated = new CountDownLatch(1);

        // The first refresh keeps its transaction open after issuing the successor, like a slow commit.
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            String successor =
                    refreshTokens.rotate(login.refreshToken()).successor().value();
            rotated.countDown();
            pause(Duration.ofMillis(500));
            return successor;
        }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<RefreshTokenService.Rotation> second =
                CompletableFuture.supplyAsync(() -> refreshTokens.rotate(login.refreshToken()));

        String successor = first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BadCredentialsException.class);
        refresh(successor).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedSessionIdIsABadRequest() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(delete(SESSIONS + "/not-a-uuid").with(bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("about:blank"));
    }

    @Test
    void sessionsNeedAToken() throws Exception {
        mockMvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized());
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private Tokens loginFromPhone(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("198.51.100.20"))
                        .header(HttpHeaders.USER_AGENT, "Phone/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private List<Map<String, Object>> sessions(String accessToken) throws Exception {
        String body = mockMvc.perform(get(SESSIONS).with(bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"refreshToken":"%s"}""", refreshToken)));
    }
}
