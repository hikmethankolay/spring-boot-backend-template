package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import com.hikmethankolay.template.common.SecureTokens;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/** {@code POST /auth/register} does not reveal whether an email address already has an account. */
class RegisterEnumerationIntegrationTest extends IntegrationTest {

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void registeredAndNewAddressesGetTheSameResponseAndNothingIsCreated() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String registered = owner + "@example.com";
        String fresh = uniqueUsername() + "@example.com";
        // Signing the owner up sent a sign-up email to the address a moment ago.
        forgetCooldown(registered);

        MockHttpServletResponse forFresh = start(fresh);
        MockHttpServletResponse forRegistered = start(registered.toUpperCase());

        assertThat(forRegistered.getStatus()).isEqualTo(forFresh.getStatus()).isEqualTo(202);
        assertThat(forRegistered.getContentAsString())
                .isEqualTo(forFresh.getContentAsString())
                .isEmpty();
        assertThat(forRegistered.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, fresh))
                .isZero();
        // The owner of the registered address is told, without a link; the new address gets its sign-up link.
        assertThat(mail.latest(registered, "You already have an account")
                        .orElseThrow()
                        .getText())
                .doesNotContain("token=");
        assertThat(mail.latest(fresh, "Finish creating your account")).isPresent();
    }

    @Test
    void responseTimeDoesNotRevealARegisteredAddress() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String registered = owner + "@example.com";
        // Warm up both paths, so JIT compilation and pool growth are not measured.
        for (int i = 0; i < 3; i++) {
            start(uniqueUsername() + "@example.com");
            forgetCooldown(registered);
            start(registered);
        }

        long[] fresh = new long[7];
        long[] taken = new long[7];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = timed(uniqueUsername() + "@example.com");
            // Compare first attempts on both paths, not a first attempt with a throttled one.
            forgetCooldown(registered);
            taken[i] = timed(registered);
        }

        assertThat(Math.abs(median(fresh) - median(taken)))
                .isLessThan(Duration.ofMillis(50).toNanos());
    }

    @Test
    void oneSignUpEmailPerAddressAndMinuteWhetherOrNotItIsRegistered() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String registered = owner + "@example.com";
        String fresh = uniqueUsername() + "@example.com";

        start(fresh);
        var firstLink = mail.latest(fresh, "Finish creating your account").orElseThrow();
        start(fresh);
        start(registered);

        assertThat(mail.latest(fresh, "Finish creating your account")).containsSame(firstLink);
        // The owner's own sign-up a moment ago counts too: no notice within the minute.
        assertThat(mail.latest(registered, "You already have an account")).isEmpty();
    }

    private void forgetCooldown(String address) {
        redis.delete("spring-boot-template:sign-up-cooldown:" + SecureTokens.hash(address));
    }

    private long timed(String email) throws Exception {
        long start = System.nanoTime();
        start(email);
        return System.nanoTime() - start;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private MockHttpServletResponse start(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", email)))
                .andReturn()
                .getResponse();
    }
}
