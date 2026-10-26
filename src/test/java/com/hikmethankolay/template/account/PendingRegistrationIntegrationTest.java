package com.hikmethankolay.template.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;

/** Pending sign-ups and verification links over time; rows are backdated instead of waiting. */
class PendingRegistrationIntegrationTest extends IntegrationTest {

    private static final String CONFIRM = "Confirm your email";

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountService accounts;

    @Test
    void aSecondSignUpWithinTheCooldownIsIgnored() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        signUp(username, address);
        SimpleMailMessage first = mail.latest(address, CONFIRM).orElseThrow();

        signUp(uniqueUsername(), address);

        assertThat(mail.latest(address, CONFIRM)).containsSame(first);
        assertThat(pendingFor(address)).isEqualTo(1);
    }

    @Test
    void resendAfterTheCooldownReplacesTheLink() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        signUp(username, address);
        String oldLink = CapturingMailSender.token(mail.latest(address, CONFIRM).orElseThrow());
        jdbc.update(
                "UPDATE pending_registrations SET created_at = now() - interval '2 minutes' WHERE email = ?", address);

        resend(address);

        String newLink = CapturingMailSender.token(mail.latest(address, CONFIRM).orElseThrow());
        assertThat(newLink).isNotEqualTo(oldLink);
        verify(oldLink, 400);
        verify(newLink, 204);
        login(username, PASSWORD);
    }

    @Test
    void anExpiredSignUpCanNeitherBeConfirmedNorResent() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        signUp(username, address);
        SimpleMailMessage first = mail.latest(address, CONFIRM).orElseThrow();
        jdbc.update(
                "UPDATE pending_registrations SET created_at = now() - interval '2 days', "
                        + "expires_at = now() - interval '1 day' WHERE email = ?",
                address);

        verify(CapturingMailSender.token(first), 400);
        resend(address);

        assertThat(mail.latest(address, CONFIRM)).containsSame(first);
    }

    @Test
    void resendReachesAnAccountWhoseNewAddressIsUnconfirmed() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        String newAddress = "moved-" + username + "@example.com";
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s","currentPassword":"%s"}""", newAddress, PASSWORD)))
                .andExpect(status().isOk());
        SimpleMailMessage first = mail.latest(newAddress, CONFIRM).orElseThrow();

        // Within the cooldown nothing is sent; after it, a new link replaces the old one.
        resend(newAddress);
        assertThat(mail.latest(newAddress, CONFIRM)).containsSame(first);
        jdbc.update(
                "UPDATE account_tokens SET created_at = now() - interval '2 minutes' "
                        + "WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                username);
        resend(newAddress);
        assertThat(mail.latest(newAddress, CONFIRM)).isPresent().get().isNotSameAs(first);

        // Once confirmed, the address gets nothing.
        confirmEmail(newAddress);
        SimpleMailMessage confirmed = mail.latest(newAddress, CONFIRM).orElseThrow();
        jdbc.update(
                "UPDATE account_tokens SET created_at = now() - interval '2 minutes' "
                        + "WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                username);
        resend(newAddress);
        assertThat(mail.latest(newAddress, CONFIRM)).containsSame(confirmed);
    }

    @Test
    void expiredSignUpsAreDeletedByTheCleanup() throws Exception {
        String address = uniqueUsername() + "@example.com";
        signUp(uniqueUsername(), address);
        jdbc.update(
                "UPDATE pending_registrations SET expires_at = now() - interval '1 second' WHERE email = ?", address);

        accounts.deleteExpiredTokens();

        assertThat(pendingFor(address)).isZero();
    }

    private Integer pendingFor(String address) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pending_registrations WHERE email = ?", Integer.class, address);
    }

    private void signUp(String username, String address) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s","email":"%s","password":"%s"}""", username, address, PASSWORD)))
                .andExpect(status().isAccepted());
    }

    private void resend(String address) throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", address)))
                .andExpect(status().isAccepted());
    }

    private void verify(String token, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s"}""", token)))
                .andExpect(status().is(expectedStatus));
    }
}
