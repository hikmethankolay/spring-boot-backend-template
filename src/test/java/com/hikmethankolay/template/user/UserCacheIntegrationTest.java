package com.hikmethankolay.template.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.config.CacheConfig.CacheNames;
import com.hikmethankolay.template.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the Redis user cache: hits, JSON round-trip and eviction on every write. */
class UserCacheIntegrationTest extends IntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Cache usersCache() {
        return cacheManager.getCache(CacheNames.USERS);
    }

    @Test
    void lookupIsServedFromCache() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = login(username, PASSWORD);

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token))).andExpect(status().isOk());
        assertThat(usersCache().get(id, UserResponse.class))
                .isNotNull()
                .satisfies(cached -> assertThat(cached.username()).isEqualTo(username));

        // Change the row behind the cache's back: the cached value is still returned.
        jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", "changed-in-db@example.com", id);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(username + "@example.com"));
    }

    @Test
    void profileUpdateEvictsEntry() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = login(username, PASSWORD);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token))).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"updated-%s@example.com","currentPassword":"%s"}""", username, PASSWORD)))
                .andExpect(status().isOk());

        assertThat(usersCache().get(id)).isNull();
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.email").value("updated-" + username + "@example.com"));
    }

    @Test
    void passwordChangeAndAdminUpdateEvictEntry() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = login(username, PASSWORD);
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token))).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","newPassword":"Changed#456"}""", PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(usersCache().get(id)).isNull();

        mockMvc.perform(get("/api/v1/users/{id}", id).with(bearer(adminToken))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"role":"ADMIN","currentPassword":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isOk());
        assertThat(usersCache().get(id)).isNull();
        mockMvc.perform(get("/api/v1/users/{id}", id).with(bearer(adminToken)))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void deleteEvictsEntry() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long id = register(uniqueUsername());

        mockMvc.perform(get("/api/v1/users/{id}", id).with(bearer(adminToken))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/users/{id}", id).with(bearer(adminToken)))
                .andExpect(status().isNoContent());
        assertThat(usersCache().get(id)).isNull();

        // Not served from a stale cache entry.
        mockMvc.perform(get("/api/v1/users/{id}", id).with(bearer(adminToken))).andExpect(status().isNotFound());
    }
}
