package com.hikmethankolay.template.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;

class AccountIntegrationTest extends IntegrationTest {

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private SimpleMailMessage latestMail(String username, String subjectPart) {
        return mail.latest(username + "@example.com", subjectPart)
                .orElseThrow(() -> new AssertionError("No '" + subjectPart + "' email for " + username));
    }

    /** Signs up without opening the link, so the account does not exist yet. */
    private void signUp(String username) throws Exception {
        postJson("/api/v1/auth/register", json("""
                {"username":"%s","email":"%s@example.com","password":"%s"}""", username, username, PASSWORD), 202);
    }

    private void postJson(String path, String body, int expectedStatus) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void signUpCreatesTheAccountOnlyWhenTheLinkIsOpened() throws Exception {
        String username = uniqueUsername();
        signUp(username);

        // Until the link is opened there is no account: login fails like for any unknown user.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isUnauthorized());
        SimpleMailMessage verification = latestMail(username, "Confirm your email");
        assertThat(verification.getText()).contains("http://localhost:3000/verify-email?token=");
        String verificationToken = CapturingMailSender.token(verification);

        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", verificationToken), 204);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(login(username, PASSWORD))))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.role").value("USER"));

        // Tokens are single-use.
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", verificationToken), 400);
    }

    @Test
    void aUsernameTakenBeforeTheLinkIsOpenedIsAConflict() throws Exception {
        String username = uniqueUsername();
        signUp(username);
        String link = CapturingMailSender.token(latestMail(username, "Confirm your email"));
        // Someone else signs up with the same username, with another address, and confirms first.
        postJson("/api/v1/auth/register", json("""
                {"username":"%s","email":"other-%s@example.com","password":"%s"}""", username, username, PASSWORD), 202);
        confirmEmail("other-" + username + "@example.com");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s"}""", link)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:username-taken"));
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"definitely-not-a-real-token"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The link is invalid or has expired."));
    }

    @Test
    void resendWithinTheCooldownSendsNothingAndKeepsTheLink() throws Exception {
        String username = uniqueUsername();
        signUp(username);
        SimpleMailMessage first = latestMail(username, "Confirm your email");

        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s@example.com"}""", username), 202);

        assertThat(latestMail(username, "Confirm your email")).isSameAs(first);
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", CapturingMailSender.token(first)), 204);
    }

    @Test
    void repeatedResetRequestsSendOneEmail() throws Exception {
        String username = uniqueUsername();
        register(username);

        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);
        SimpleMailMessage first = latestMail(username, "Reset your password");
        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);

        assertThat(latestMail(username, "Reset your password")).isSameAs(first);
    }

    @Test
    void passwordResetSetsNewPassword() throws Exception {
        String username = uniqueUsername();
        register(username);

        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);
        SimpleMailMessage reset = latestMail(username, "Reset your password");
        assertThat(reset.getText()).contains("http://localhost:3000/reset-password?token=");

        postJson("/api/v1/auth/password-reset/confirm", json("""
                {"token":"%s","newPassword":"Reset#4567-passphrase"}""", CapturingMailSender.token(reset)), 204);

        login(username, "Reset#4567-passphrase");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.2.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredResetLinkIsRejected() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);
        String token = CapturingMailSender.token(latestMail(username, "Reset your password"));
        jdbc.update("UPDATE account_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);

        postJson("/api/v1/auth/password-reset/confirm", json("""
                {"token":"%s","newPassword":"Reset#4567-passphrase"}""", token), 400);
    }

    @Test
    void passwordResetValidatesTheNewPassword() throws Exception {
        String username = uniqueUsername();
        register(username);
        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);

        postJson(
                "/api/v1/auth/password-reset/confirm",
                json("""
                {"token":"%s","newPassword":"weak"}""", CapturingMailSender.token(latestMail(username, "Reset your password"))),
                400);
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        String address = uniqueUsername() + "@example.com";

        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s"}""", address), 202);
        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s"}""", address), 202);

        assertThat(mail.latest(address, "")).isEmpty();
    }

    @Test
    void changingEmailRequiresVerificationAgain() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        String newAddress = "moved-" + username + "@example.com";
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s","currentPassword":"%s"}""", newAddress, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));

        assertThat(mail.latest(newAddress, "Confirm your email")).isPresent();
    }

    @Test
    void changingEmailNeedsTheCurrentPassword() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        for (String body : new String[] {json("""
                        {"email":"stolen-%s@example.com"}""", username), json("""
                        {"email":"stolen-%s@example.com","currentPassword":"Wrong#123"}""", username)}) {
            mockMvc.perform(patch("/api/v1/users/me")
                            .with(bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(endsWith("wrong-password")));
        }
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.email").value(username + "@example.com"));
    }

    @Test
    void changingEmailRevokesLinksSentToTheOldAddress() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);
        String resetToken = CapturingMailSender.token(latestMail(username, "Reset your password"));
        changeEmail(token, "moved-" + username + "@example.com");
        String verificationToken =
                CapturingMailSender.token(mail.latest("moved-" + username + "@example.com", "Confirm your email")
                        .orElseThrow());

        changeEmail(token, "moved-again-" + username + "@example.com");

        postJson("/api/v1/auth/password-reset/confirm", json("""
                {"token":"%s","newPassword":"Reset#4567-passphrase"}""", resetToken), 400);
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", verificationToken), 400);
    }

    private void changeEmail(String token, String address) throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s","currentPassword":"%s"}""", address, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void emptyTokenIsAValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'token')]").exists());
    }

    @Test
    void everyEmailFieldUsesTheSameRule() throws Exception {
        String tooLong = "a".repeat(250) + "@example.com";
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s","email":"%s","password":"%s"}""", uniqueUsername(), tooLong, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }
}
