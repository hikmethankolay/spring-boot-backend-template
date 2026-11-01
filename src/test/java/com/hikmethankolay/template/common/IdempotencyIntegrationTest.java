package com.hikmethankolay.template.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class IdempotencyIntegrationTest extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void aRetryWithTheSameKeyGetsTheFirstResponseInsteadOfASecondNote() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        String key = UUID.randomUUID().toString();

        String first = createNote(token, key, "Groceries")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(first, "$.id")).longValue();

        createNote(token, key, "Groceries")
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/api/v1/notes/" + id))
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(get("/api/v1/notes").with(bearer(token)))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void aKeptResponseExpiresWithItsKey() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        String token = login(username, PASSWORD);
        String key = UUID.randomUUID().toString();
        String claimKey = "spring-boot-template:idempotency:{" + userId + "}:" + SecureTokens.hash(key);

        createNote(token, key, "Groceries").andExpect(status().isCreated());

        // Redis runs with noeviction: both keys the filter keeps must carry the 24-hour TTL.
        for (String stored : List.of(claimKey, claimKey + ":response")) {
            assertThat(redis.getExpire(stored))
                    .isBetween(
                            Duration.ofHours(23).toSeconds(),
                            Duration.ofHours(24).toSeconds());
        }
    }

    @Test
    void aKeyCannotBeReusedForAnotherRequest() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        String key = UUID.randomUUID().toString();
        createNote(token, key, "Groceries").andExpect(status().isCreated());

        createNote(token, key, "Something else")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:problem:idempotency-key-reused"));
    }

    @Test
    void keysBelongToTheirCaller() throws Exception {
        String key = UUID.randomUUID().toString();
        String alice = registerAndLogin(uniqueUsername());
        String bob = registerAndLogin(uniqueUsername());
        createNote(alice, key, "Groceries").andExpect(status().isCreated());

        createNote(bob, key, "Groceries")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
    }

    @Test
    void aRetryWhileTheFirstRequestRunsIsRefused() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        String token = login(username, PASSWORD);
        String key = UUID.randomUUID().toString();
        // What the filter stores while the first request runs: the claim, without a response yet.
        redis.opsForValue()
                .set(
                        "spring-boot-template:idempotency:{" + userId + "}:" + SecureTokens.hash(key),
                        "fingerprint",
                        Duration.ofMinutes(1));

        createNote(token, key, "Groceries")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:idempotency-key-in-use"));
    }

    @Test
    void aKeyMustNotBeEmptyOrTooLong() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        createNote(token, "x".repeat(256), "Groceries")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:idempotency-key-invalid"));
    }

    private ResultActions createNote(String token, String key, String title) throws Exception {
        return mockMvc.perform(post("/api/v1/notes")
                .with(bearer(token))
                .header(IdempotencyFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"title":"%s","body":"milk"}""", title)));
    }
}
