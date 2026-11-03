package com.hikmethankolay.template.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class SecurityEventIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creatingTheOwnerAtStartupIsRecorded() {
        Integer recorded = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events e JOIN users u ON u.id = e.user_id
                WHERE e.action = 'admin.owner_bootstrapped' AND u.username = ?""", Integer.class, ADMIN_USERNAME);

        assertThat(recorded).isPositive();
    }

    @Test
    void usersSeeTheirOwnActivityNewestFirst() throws Exception {
        String username = uniqueUsername();
        register(username);
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.40.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"Wrong#123"}""", username)))
                .andExpect(status().isUnauthorized());
        String token = login(username, PASSWORD);

        mockMvc.perform(get("/api/v1/users/me/security-events").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].event").value("auth.login_succeeded"))
                .andExpect(jsonPath("$.content[1].event").value("auth.login_failed"))
                .andExpect(jsonPath("$.content[1].clientIp").value("10.0.40.1"))
                .andExpect(jsonPath("$.content[1].occurredAt").exists())
                // The details hold a hash of what was typed as the username, possibly a mistyped password.
                .andExpect(jsonPath("$.content[1].details").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").isNumber());
    }

    @Test
    void otherUsersCannotReadThemButAdministratorsCan() throws Exception {
        long id = register(uniqueUsername());
        String other = registerAndLogin(uniqueUsername());
        String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/users/{id}/security-events", id).with(bearer(other)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/{id}/security-events", id)
                        .with(bearer(admin))
                        .param("sort", "occurredAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].event").value("user.registered"));
    }

    @Test
    void anotherUsersIdIsNotShownAsTheActor() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = login(username, PASSWORD);
        String insert = "INSERT INTO audit_events (action, user_id, actor_id) VALUES (?, ?, ?)";
        jdbc.update(insert, "admin.user_updated", id, "999999");
        jdbc.update(insert, "user.password_changed", id, String.valueOf(id));

        mockMvc.perform(get("/api/v1/users/me/security-events").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].event").value("user.password_changed"))
                .andExpect(jsonPath("$.content[0].actorId").value(String.valueOf(id)))
                .andExpect(jsonPath("$.content[1].event").value("admin.user_updated"))
                .andExpect(jsonPath("$.content[1].actorId").value(SecurityEvents.OTHER_ACTOR));
    }

    @Test
    void onlyTheTimeCanBeSortedBy() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(get("/api/v1/users/me/security-events")
                        .with(bearer(token))
                        .param("sort", "clientIp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-sort"));
    }
}
