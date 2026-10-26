package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Test
    void registeredAndNewAddressesGetTheSameResponseAndNothingIsCreated() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String registered = owner + "@example.com";
        String fresh = uniqueUsername() + "@example.com";

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
            start(registered);
        }

        long[] fresh = new long[7];
        long[] taken = new long[7];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = timed(uniqueUsername() + "@example.com");
            taken[i] = timed(registered);
        }

        assertThat(Math.abs(median(fresh) - median(taken)))
                .isLessThan(Duration.ofMillis(50).toNanos());
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
