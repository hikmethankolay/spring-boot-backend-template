package com.hikmethankolay.template.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.mail.EmailOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountIntegrationTest extends IntegrationTest {

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SimpleMailMessage latestMail(String username, String subjectPart) {
        return mail.latest(username + "@example.com", subjectPart)
                .orElseThrow(() -> new AssertionError("No '" + subjectPart + "' email for " + username));
    }

    /** Posts and then runs the outbox, which sends the emails anonymous requests leave to it. */
    private void postJson(String path, String body, int expectedStatus) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
        outbox.sendDue(Instant.now());
    }

    @Test
    void aRolledBackSignUpDoesNotHoldTheCooldown() {
        String address = uniqueUsername() + "@example.com";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accounts.signUp(address);
            status.setRollbackOnly();
        });

        // The rolled-back attempt queued nothing, so the retry within the cooldown still sends the link.
        accounts.signUp(address);
        outbox.sendDue(Instant.now());
        assertThat(mail.latest(address, "Finish creating your account")).isPresent();
    }

    @Test
    void aSignUpLinkCreatesTheAccountOnce() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        postJson("/api/v1/auth/register", json("""
                {"email":"%s"}""", address), 202);

        // Until the link is used there is no account: login fails like for any unknown user.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", address, PASSWORD)))
                .andExpect(status().isUnauthorized());
        SimpleMailMessage link =
                mail.latest(address, "Finish creating your account").orElseThrow();
        assertThat(link.getText()).contains("http://localhost:3000/sign-up?token=");
        assertThat(mail.html(link))
                .hasValueSatisfying(html -> assertThat(html)
                        .contains("href=\"http://localhost:3000/sign-up?token=" + CapturingMailSender.token(link)
                                + "\""));
        String body = json("""
                {"token":"%s","username":"%s","password":"%s"}""", CapturingMailSender.token(link), username, PASSWORD);

        postJson("/api/v1/auth/register/complete", body, 201);
        login(username, PASSWORD);
        // A link works once.
        mockMvc.perform(post("/api/v1/auth/register/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(username, username + "2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-token"));
    }

    @Test
    void aUsedLinkCannotCreateAnotherAccountAfterTheFirstIsDeleted() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        String token = registerAndLogin(username);
        String link = signUpToken(address);
        mockMvc.perform(delete("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/register/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s","username":"%s","password":"%s"}""", link, username + "x", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-token"));
    }

    @Test
    void onlySignUpTokensCompleteASignUp() throws Exception {
        String accessToken = registerAndLogin(uniqueUsername());

        for (String token : new String[] {"definitely-not-a-real-token", accessToken}) {
            mockMvc.perform(post("/api/v1/auth/register/complete")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("""
                                    {"token":"%s","username":"%s","password":"%s"}""", token, uniqueUsername(), PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem:invalid-token"));
        }
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
    void resendReachesOnlyAnUnconfirmedAddressAndRespectsTheCooldown() throws Exception {
        // Sign-ups are verified when the account is created; an address an administrator set is not.
        String username = uniqueUsername();
        long id = register(username);
        String newAddress = "moved-" + username + "@example.com";
        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .with(bearer(login(ADMIN_USERNAME, ADMIN_PASSWORD)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", newAddress)))
                .andExpect(status().isOk());
        SimpleMailMessage first = mail.latest(newAddress, "Confirm your email").orElseThrow();

        // Within the cooldown nothing is sent and the first link still works later.
        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s"}""", newAddress), 202);
        assertThat(mail.latest(newAddress, "Confirm your email")).containsSame(first);
        // After it, a new link replaces the old one.
        jdbc.update(
                "UPDATE account_tokens SET created_at = now() - interval '2 minutes' "
                        + "WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                username);
        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s"}""", newAddress), 202);
        SimpleMailMessage second = mail.latest(newAddress, "Confirm your email").orElseThrow();
        assertThat(second).isNotSameAs(first);
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", CapturingMailSender.token(first)), 400);
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", CapturingMailSender.token(second)), 204);

        // A confirmed address gets nothing.
        jdbc.update(
                "UPDATE account_tokens SET created_at = now() - interval '2 minutes' "
                        + "WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                username);
        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s"}""", newAddress), 202);
        assertThat(mail.latest(newAddress, "Confirm your email")).containsSame(second);
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
    void theResetEmailIsLeftToTheOutboxSoKnownAddressesAreNotSlower() throws Exception {
        String username = uniqueUsername();
        register(username);

        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s@example.com"}""", username)))
                .andExpect(status().isAccepted());
        // Not sent from the request: the SMTP round trip would tell which addresses have accounts.
        assertThat(mail.latest(username + "@example.com", "Reset your password"))
                .isEmpty();

        outbox.sendDue(Instant.now());
        assertThat(mail.latest(username + "@example.com", "Reset your password"))
                .isPresent();
    }

    @Test
    void aResetLinkWorksOnceEvenForConcurrentRequests() throws Exception {
        String username = uniqueUsername();
        register(username);
        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);
        String token = CapturingMailSender.token(latestMail(username, "Reset your password"));

        CountDownLatch firstUsedIt = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            accounts.resetPassword(token, "First#4567-passphrase");
            firstUsedIt.countDown();
            await(commit);
        }));
        assertThat(firstUsedIt.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second =
                CompletableFuture.runAsync(() -> accounts.resetPassword(token, "Second#4567-passphrase"));
        // The second request must wait for the first to commit instead of using the token too.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                        + " AND query ILIKE '%account_tokens%'",
                                Integer.class)
                        > 0);
        commit.countDown();
        first.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ApiException.class)
                .cause()
                .hasMessageContaining("invalid");
        login(username, "First#4567-passphrase");
    }

    @Test
    void parallelResetRequestsSendOneEmail() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        String address = username + "@example.com";

        CountDownLatch firstIssued = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<String> issued = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            accounts.requestPasswordReset(address);
            issued.set(resetTokenHash(userId));
            firstIssued.countDown();
            await(commit);
        }));
        assertThat(firstIssued.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> accounts.requestPasswordReset(address));
        // The second request must wait for the first to commit, then see its token, instead of sending another.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                        + " AND query ILIKE '%pg_advisory_xact_lock%'",
                                Integer.class)
                        > 0);
        commit.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(resetTokenHash(userId)).isEqualTo(issued.get());
    }

    private String resetTokenHash(long userId) {
        return jdbc.queryForObject(
                "SELECT token_hash FROM account_tokens WHERE user_id = ? AND type = 'PASSWORD_RESET'",
                String.class,
                userId);
    }

    @Test
    void aTokenOnlyWorksForItsOwnPurpose() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        postJson("/api/v1/auth/password-reset", json("""
                {"email":"%s@example.com"}""", username), 202);
        String resetToken = CapturingMailSender.token(latestMail(username, "Reset your password"));
        String newAddress = "moved-" + username + "@example.com";
        changeEmail(token, newAddress);
        String verificationToken = CapturingMailSender.token(mail.latest(newAddress, CHANGE_MAIL)
                .orElseThrow(() -> new AssertionError("No verification email for " + newAddress)));

        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", resetToken), 400);
        postJson("/api/v1/auth/password-reset/confirm", json("""
                {"token":"%s","newPassword":"Reset#4567-passphrase"}""", verificationToken), 400);

        login(username, PASSWORD);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.pendingEmail").value(newAddress));
    }

    @Test
    void anEmailChangeDoesNotTellWhetherTheAddressIsTaken() throws Exception {
        String taken = uniqueUsername();
        register(taken);
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        String takenAddress = taken + "@example.com";

        // Same answer as for a free address; the conflict only shows to whoever opens the link sent to it.
        changeEmail(token, takenAddress);
        String link = CapturingMailSender.token(mail.latest(takenAddress, CHANGE_MAIL)
                .orElseThrow(() -> new AssertionError("No verification email for " + takenAddress)));
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s"}""", link)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:email-taken"));

        login(username + "@example.com", PASSWORD);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
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
    void aNewEmailReplacesTheOldOneOnlyOnceTheLinkSentToItIsOpened() throws Exception {
        String username = uniqueUsername();
        String oldAddress = username + "@example.com";
        String token = registerAndLogin(username);

        String newAddress = "moved-" + username + "@example.com";
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s","currentPassword":"%s"}""", newAddress, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(oldAddress))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.pendingEmail").value(newAddress));
        // Until then the old address is the account's: it logs in and gets the password resets.
        login(oldAddress, PASSWORD);

        confirmEmail(newAddress);

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.email").value(newAddress))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.pendingEmail").doesNotExist());
        login(newAddress, PASSWORD);
    }

    @Test
    void resendAlsoReachesAnAddressAnEmailChangeIsWaitingFor() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        String newAddress = "moved-" + username + "@example.com";
        changeEmail(token, newAddress);
        SimpleMailMessage first = mail.latest(newAddress, CHANGE_MAIL).orElseThrow();

        // Within the cooldown nothing is sent.
        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s"}""", newAddress), 202);
        assertThat(mail.latest(newAddress, CHANGE_MAIL)).containsSame(first);
        // After it, a new link replaces the lost one.
        jdbc.update(
                "UPDATE account_tokens SET created_at = now() - interval '2 minutes' "
                        + "WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                username);
        postJson("/api/v1/auth/verify-email/resend", json("""
                {"email":"%s"}""", newAddress), 202);
        SimpleMailMessage second = mail.latest(newAddress, CHANGE_MAIL).orElseThrow();
        assertThat(second).isNotSameAs(first);
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", CapturingMailSender.token(first)), 400);
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", CapturingMailSender.token(second)), 204);

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.email").value(newAddress));
    }

    @Test
    void endingTheSessionsCancelsAPendingEmailChange() throws Exception {
        // Someone with the password asks to move the account to their own address...
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        String theirs = "taken-over-" + username + "@example.com";
        changeEmail(token, theirs);
        String link = CapturingMailSender.token(mail.latest(theirs, CHANGE_MAIL).orElseThrow());

        // ...and the owner changes the password, which ends every session.
        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","newPassword":"Changed#456-passphrase"}""", PASSWORD)))
                .andExpect(status().isNoContent());

        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", link), 400);
        String fresh = login(username, "Changed#456-passphrase");
        mockMvc.perform(get("/api/v1/users/me").with(bearer(fresh)))
                .andExpect(jsonPath("$.email").value(username + "@example.com"))
                .andExpect(jsonPath("$.pendingEmail").doesNotExist());
    }

    @Test
    void sendingTheCurrentEmailAgainCancelsAPendingChange() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        changeEmail(token, "moved-" + username + "@example.com");
        String link = CapturingMailSender.token(
                mail.latest("moved-" + username + "@example.com", CHANGE_MAIL).orElseThrow());

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s@example.com"}""", username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingEmail").doesNotExist());
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", link), 400);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.email").value(username + "@example.com"));
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
                            .with(fromIp("10.0.20.5"))
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
        String firstLink = CapturingMailSender.token(
                mail.latest("moved-" + username + "@example.com", CHANGE_MAIL).orElseThrow());

        // A newer change replaces the link of the first.
        changeEmail(token, "moved-again-" + username + "@example.com");
        postJson("/api/v1/auth/verify-email", json("""
                {"token":"%s"}""", firstLink), 400);
        confirmEmail("moved-again-" + username + "@example.com");

        postJson("/api/v1/auth/password-reset/confirm", json("""
                {"token":"%s","newPassword":"Reset#4567-passphrase"}""", resetToken), 400);
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
                                {"email":"%s"}""", tooLong)))
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
