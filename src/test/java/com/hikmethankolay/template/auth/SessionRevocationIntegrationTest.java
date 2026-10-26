package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/** Changing a password or role must end every existing session immediately. */
class SessionRevocationIntegrationTest extends IntegrationTest {

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transaction;

    private void expectSessionEnded(Tokens tokens) throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(bearer(tokens.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordEndsEverySession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens laptop = loginTokens(username, PASSWORD);
        Tokens phone = loginTokens(username, PASSWORD);

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(laptop.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","newPassword":"Changed#456-passphrase"}""", PASSWORD)))
                .andExpect(status().isNoContent());

        expectSessionEnded(laptop);
        expectSessionEnded(phone);
        // A login right after the change (same second) is not caught by the revocation.
        String fresh = login(username, "Changed#456-passphrase");
        mockMvc.perform(get("/api/v1/users/me").with(bearer(fresh))).andExpect(status().isOk());
    }

    @Test
    void passwordResetEndsEverySession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens session = loginTokens(username, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s@example.com"}""", username)))
                .andExpect(status().isAccepted());
        String resetToken = CapturingMailSender.token(
                mail.latest(username + "@example.com", "Reset your password").orElseThrow());
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s","newPassword":"Reset#4567-passphrase"}""", resetToken)))
                .andExpect(status().isNoContent());

        expectSessionEnded(session);
    }

    @Test
    void roleChangeEndsTheUsersSessionsSoTheNewRoleAppliesImmediately() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        Tokens session = loginTokens(username, PASSWORD);
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"role":"ADMIN","currentPassword":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isOk());

        expectSessionEnded(session);
        mockMvc.perform(get("/api/v1/users").with(bearer(login(username, PASSWORD))))
                .andExpect(status().isOk());
        // The administrator's own session is unaffected.
        mockMvc.perform(get("/api/v1/users/me").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN_USERNAME));
    }

    @Test
    void profileChangesKeepTheSession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens session = loginTokens(username, PASSWORD);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(session.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s"}""", uniqueUsername())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(session.accessToken())))
                .andExpect(status().isOk());
    }

    @Test
    void refreshTokenIssuedBeforeTheSessionsEndedIsRejected() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        Tokens session = loginTokens(username, PASSWORD);

        // Like a token minted by a login that raced a password change: it escaped the bulk delete.
        transaction.executeWithoutResult(
                status -> users.findById(id).orElseThrow().endSessions());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", session.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleWriteCannotRevertANewerRoleChange() throws Exception {
        long id = register(uniqueUsername());
        User stale = users.findById(id).orElseThrow();

        transaction.executeWithoutResult(
                status -> users.findById(id).orElseThrow().setRole(Role.ADMIN));
        stale.setUsername(uniqueUsername());

        assertThatThrownBy(() -> users.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }
}
