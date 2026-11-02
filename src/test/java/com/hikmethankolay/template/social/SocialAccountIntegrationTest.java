package com.hikmethankolay.template.social;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.RequestBuilder;

/** Users see the GitHub and Google accounts linked to theirs and can unlink them with their password. */
class SocialAccountIntegrationTest extends IntegrationTest {

    private static final String ACCOUNTS = "/api/v1/users/me/social-accounts";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listShowsTheLinkedAccountsWithoutTheProvidersUserId() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        String token = login(username, PASSWORD);
        long github = link(userId, "github");
        link(userId, "google");

        mockMvc.perform(get(ACCOUNTS).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].provider", Matchers.contains("github", "google")))
                .andExpect(jsonPath("$[0].id").value(github))
                .andExpect(jsonPath("$[0].linkedAt").exists())
                .andExpect(jsonPath("$[0].subject").doesNotExist());
    }

    @Test
    void unlinkingNeedsThePasswordAndRemovesOnlyThatAccount() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        String token = login(username, PASSWORD);
        long github = link(userId, "github");
        link(userId, "google");

        mockMvc.perform(unlink(token, github, "wrong-password-here"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        mockMvc.perform(unlink(token, github, PASSWORD)).andExpect(status().isNoContent());

        mockMvc.perform(get(ACCOUNTS).with(bearer(token)))
                .andExpect(jsonPath("$[*].provider", Matchers.contains("google")));
        mockMvc.perform(unlink(token, github, PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:social-account-not-found"));
    }

    @Test
    void anotherUsersLinkIsNotFound() throws Exception {
        long owner = register(uniqueUsername());
        long ownersLink = link(owner, "github");
        String intruder = uniqueUsername();
        register(intruder);

        mockMvc.perform(unlink(login(intruder, PASSWORD), ownersLink, PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:social-account-not-found"));
        mockMvc.perform(get(ACCOUNTS).with(bearer(login(intruder, PASSWORD))))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void linkedAccountsNeedAToken() throws Exception {
        mockMvc.perform(get(ACCOUNTS)).andExpect(status().isUnauthorized());
    }

    private long link(long userId, String provider) {
        return jdbc.queryForObject(
                "INSERT INTO social_identities (user_id, provider, subject) VALUES (?, ?, gen_random_uuid()::text) "
                        + "RETURNING id",
                Long.class,
                userId,
                provider);
    }

    /** Unlinks from its own IP: wrong passwords count against it. */
    private static RequestBuilder unlink(String token, long id, String password) {
        return delete(ACCOUNTS + "/{id}", id)
                .with(bearer(token))
                .with(fromIp("10.0.21.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"currentPassword":"%s"}""", password));
    }
}
