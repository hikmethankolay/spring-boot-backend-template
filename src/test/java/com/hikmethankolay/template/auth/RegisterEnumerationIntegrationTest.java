package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mock.web.MockHttpServletResponse;

/** {@code POST /auth/register} does not reveal whether an email address already has an account. */
class RegisterEnumerationIntegrationTest extends IntegrationTest {

    private static final String NOTICE = "You already have an account";

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registeredAddressGetsTheSameResponseAndItsOwnerAnEmail() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String address = owner + "@example.com";

        MockHttpServletResponse fresh = signUp(uniqueUsername(), uniqueUsername() + "@example.com", PASSWORD);
        MockHttpServletResponse taken = signUp(uniqueUsername(), address.toUpperCase(), PASSWORD);

        assertThat(taken.getStatus()).isEqualTo(fresh.getStatus()).isEqualTo(202);
        assertThat(taken.getContentAsString())
                .isEqualTo(fresh.getContentAsString())
                .isEmpty();
        assertThat(taken.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, address))
                .isEqualTo(1);
        SimpleMailMessage notice = mail.latest(address, NOTICE).orElseThrow();
        assertThat(CapturingMailSender.token(notice)).isNotBlank();
    }

    @Test
    void neitherLoginNorTheUsernameRevealsARegisteredAddress() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String probeNew = uniqueUsername();
        String probeTaken = uniqueUsername();

        signUp(probeNew, uniqueUsername() + "@example.com", PASSWORD);
        signUp(probeTaken, owner + "@example.com", PASSWORD);

        // No account exists for either probe until a link is opened: login and the username look the same.
        for (String probe : new String[] {probeNew, probeTaken}) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(fromIp("10.0.77.1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("""
                                    {"identifier":"%s","password":"%s"}""", probe, PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("urn:problem:invalid-credentials"));
            assertThat(signUp(probe, uniqueUsername() + "@example.com", PASSWORD)
                            .getStatus())
                    .isEqualTo(202);
        }
    }

    @Test
    void repeatedAttemptsSendOneEmailPerCooldown() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String address = owner + "@example.com";

        signUp(uniqueUsername(), address, PASSWORD);
        SimpleMailMessage first = mail.latest(address, NOTICE).orElseThrow();
        signUp(uniqueUsername(), address, PASSWORD);

        assertThat(mail.latest(address, NOTICE)).containsSame(first);
    }

    @Test
    void passwordRulesApplyWhetherOrNotTheAddressIsRegistered() throws Exception {
        String owner = uniqueUsername();
        register(owner);

        assertThat(signUp(uniqueUsername(), owner + "@example.com", "fourteen-chars")
                        .getStatus())
                .isEqualTo(signUp(uniqueUsername(), uniqueUsername() + "@example.com", "fourteen-chars")
                        .getStatus())
                .isEqualTo(400);
        assertThat(mail.latest(owner + "@example.com", NOTICE)).isEmpty();
    }

    @Test
    void responseTimeDoesNotRevealARegisteredAddress() throws Exception {
        String owner = uniqueUsername();
        register(owner);
        String address = owner + "@example.com";
        // Warm up both paths, so JIT compilation and pool growth are not measured.
        for (int i = 0; i < 3; i++) {
            signUp(uniqueUsername(), uniqueUsername() + "@example.com", PASSWORD);
            signUp(uniqueUsername(), address, PASSWORD);
        }

        long[] fresh = new long[7];
        long[] taken = new long[7];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = timed(uniqueUsername() + "@example.com");
            taken[i] = timed(address);
        }

        assertThat(Math.abs(median(fresh) - median(taken)))
                .isLessThan(Duration.ofMillis(50).toNanos());
    }

    private long timed(String email) throws Exception {
        long start = System.nanoTime();
        signUp(uniqueUsername(), email, PASSWORD);
        return System.nanoTime() - start;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private MockHttpServletResponse signUp(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s","email":"%s","password":"%s"}""", username, email, password)))
                .andReturn()
                .getResponse();
    }
}
