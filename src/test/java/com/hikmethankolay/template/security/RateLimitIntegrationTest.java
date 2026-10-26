package com.hikmethankolay.template.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(
        properties = {
            "app.rate-limit.requests-per-minute=3",
            "app.rate-limit.routes[0].path=/api/v1/auth/password-reset/**",
            "app.rate-limit.routes[0].requests=2",
            "app.rate-limit.routes[0].window=1h"
        })
class RateLimitIntegrationTest extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    /** A fresh IP per test, so counters from other tests never interfere. */
    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10.%d.%d.%d".formatted(random.nextInt(256), random.nextInt(256), random.nextInt(1, 255));
    }

    /**
     * Waits out the last two seconds of a minute: the sliding window weighs the previous minute by the time left, so a
     * test that crosses into a new minute can count one request less than it expects.
     */
    private static void awayFromTheMinuteBoundary() throws InterruptedException {
        long untilNextMinute = 60_000 - System.currentTimeMillis() % 60_000;
        if (untilNextMinute < 2_000) {
            Thread.sleep(untilNextMinute + 50);
        }
    }

    @Test
    void requestsOverTheLimitAreRejected() throws Exception {
        awayFromTheMinuteBoundary();
        String ip = randomIp();
        for (int i = 3; i > 0; i--) {
            mockMvc.perform(get("/api/v1/users/me").with(fromIp(ip)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("RateLimit-Limit", "3"))
                    .andExpect(header().string("RateLimit-Remaining", Integer.toString(i - 1)));
        }

        mockMvc.perform(get("/api/v1/users/me").with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429));

        // Other clients are not affected.
        mockMvc.perform(get("/api/v1/users/me").with(fromIp(randomIp()))).andExpect(status().isUnauthorized());
    }

    @Test
    void thePreviousMinuteStillCounts() throws Exception {
        awayFromTheMinuteBoundary();
        String ip = randomIp();
        long previousWindow = System.currentTimeMillis() / 60_000 - 1;
        // A burst at the end of the previous minute: nearly all of it is still inside the last 60 seconds.
        redis.opsForValue().set("spring-boot-template:rate-limit:{" + ip + "}:" + previousWindow, "100000");

        mockMvc.perform(get("/api/v1/users/me").with(fromIp(ip))).andExpect(status().isTooManyRequests());
    }

    @Test
    void oneIpv6NetworkSharesOneLimit() throws Exception {
        awayFromTheMinuteBoundary();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String network = "2001:db8:%x:%x::".formatted(random.nextInt(65_536), random.nextInt(65_536));
        for (int host = 1; host <= 3; host++) {
            mockMvc.perform(get("/api/v1/users/me").with(fromIp(network + host)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(get("/api/v1/users/me").with(fromIp(network + "99"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void aRouteLimitIsCountedOnTopOfTheGlobalOne() throws Exception {
        awayFromTheMinuteBoundary();
        String ip = randomIp();
        for (int i = 2; i > 0; i--) {
            resetPassword(ip)
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("RateLimit-Limit", "2"))
                    .andExpect(header().string("RateLimit-Remaining", Integer.toString(i - 1)));
        }

        // Third request: within the global 3 per minute, over the route's 2 per hour.
        resetPassword(ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9]\\d*")));
        // Other paths only have the global limit.
        mockMvc.perform(get("/api/v1/users/me").with(fromIp(randomIp())))
                .andExpect(header().string("RateLimit-Limit", "3"));
    }

    private ResultActions resetPassword(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset")
                .with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"nobody@example.com"}"""));
    }

    @Test
    void actuatorIsNotLimited() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/actuator/health").with(fromIp(ip))).andExpect(status().isOk());
        }
    }
}
