package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Hashes from before the switch to Argon2id are rewritten at the next successful login. */
class PasswordUpgradeIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bcryptHashIsRewrittenToArgon2idAfterLogin() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        jdbc.update(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD),
                id);

        login(username, PASSWORD);

        String hash = passwordHash(id);
        assertThat(hash).startsWith("{argon2}$argon2id$");
        // The new hash works, and the next login leaves it alone.
        login(username, PASSWORD);
        assertThat(passwordHash(id)).isEqualTo(hash);
    }

    @Test
    void failedLoginKeepsTheBcryptHash() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String legacy = "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", legacy, id);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.41.7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"wrong-password-here"}""", username)))
                .andExpect(status().isUnauthorized());

        assertThat(passwordHash(id)).isEqualTo(legacy);
    }

    private String passwordHash(long id) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
    }
}
