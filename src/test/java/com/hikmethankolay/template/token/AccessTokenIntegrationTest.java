package com.hikmethankolay.template.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class AccessTokenIntegrationTest extends IntegrationTest {

    private static final String TOKENS = "/api/v1/users/me/access-tokens";

    private static final String NOTES = "/api/v1/notes";

    private static final String NOTE = "{\"title\":\"t\",\"body\":\"b\"}";

    private static final String NEW_PASSWORD = "{\"currentPassword\":\"%s\",\"newPassword\":\"Other#123-passphrase\"}";

    @Test
    void aWriteTokenActsAsItsUserOnTheApi() throws Exception {
        String username = uniqueUsername();
        register(username);
        String session = login(username, PASSWORD);
        String token = token(create(session, "script", "WRITE", 30, PASSWORD).andExpect(status().isCreated()));

        assertThat(token).startsWith("pat_").hasSize(47);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
        mockMvc.perform(post(NOTES)
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NOTE))
                .andExpect(status().isCreated());
        mockMvc.perform(get(TOKENS).with(bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("script"))
                .andExpect(jsonPath("$[0].scope").value("WRITE"))
                .andExpect(jsonPath("$[0].lastUsedAt").exists())
                .andExpect(jsonPath("$[0].token").doesNotExist());
    }

    @Test
    void aReadTokenCanOnlyRead() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        String token = token(create(session, "dashboard", "READ", 30, PASSWORD));

        mockMvc.perform(get(NOTES).with(bearer(token))).andExpect(status().isOk());
        mockMvc.perform(post(NOTES)
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NOTE))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenCannotManageTheAccount() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        String token = token(create(session, "script", "WRITE", 30, PASSWORD));

        mockMvc.perform(get(TOKENS).with(bearer(token))).andExpect(status().isForbidden());
        create(token, "another", "WRITE", 30, PASSWORD).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/me/sessions").with(bearer(token))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/me/export").with(bearer(token))).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(NEW_PASSWORD, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenCarriesItsUsersRole() throws Exception {
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String ownerToken = token(create(owner, "admin script", "READ", 30, ADMIN_PASSWORD));
        String user = registerAndLogin(uniqueUsername());
        String userToken = token(create(user, "script", "READ", 30, PASSWORD));

        mockMvc.perform(get("/api/v1/users").with(bearer(ownerToken))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").with(bearer(userToken))).andExpect(status().isForbidden());
        revokeAll(owner);
    }

    @Test
    void aRevokedTokenStopsWorkingAtOnce() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        ResultActions created = create(session, "script", "READ", 30, PASSWORD);
        String token = token(created);
        long id = id(created);

        mockMvc.perform(delete(TOKENS + "/" + id).with(bearer(session))).andExpect(status().isNoContent());

        mockMvc.perform(get(NOTES).with(bearer(token))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(TOKENS + "/" + id).with(bearer(session)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:access-token-not-found"));
    }

    @Test
    void anotherUsersTokenCannotBeRevoked() throws Exception {
        String alice = registerAndLogin(uniqueUsername());
        ResultActions created = create(alice, "script", "READ", 30, PASSWORD);
        String bob = registerAndLogin(uniqueUsername());

        mockMvc.perform(delete(TOKENS + "/" + id(created)).with(bearer(bob))).andExpect(status().isNotFound());
        mockMvc.perform(get(NOTES).with(bearer(token(created)))).andExpect(status().isOk());
    }

    @Test
    void changingThePasswordRevokesEveryToken() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        String token = token(create(session, "script", "READ", 30, PASSWORD));

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(NEW_PASSWORD, PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(NOTES).with(bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void creatingNeedsThePasswordAndALifetimeWithinTheLimit() throws Exception {
        String session = registerAndLogin(uniqueUsername());

        create(session, "script", "READ", 30, "Wrong#123-passphrase")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        create(session, "script", "READ", 366, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:access-token-lifetime-too-long"));
        create(session, "", "READ", 30, PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void aTokenCappedBelowTheOwnerRoleCannotActAsTheOwnerOrReceiveAdminEvents() throws Exception {
        // As AccessTokenService builds it for the owner when ACCESS_TOKENS_MAX_ROLE caps the role.
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long ownerId = ((Number) JsonPath.read(
                        mockMvc.perform(get("/api/v1/users/me").with(bearer(owner)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"))
                .longValue();
        long userId = register(uniqueUsername());

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .with(capped(ownerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"role\":\"ADMIN\",\"currentPassword\":\"%s\"}", ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:owner-required"));
        mockMvc.perform(post("/api/v1/webhooks")
                        .with(capped(ownerId, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:9/hook\",\"eventTypes\":[\"user.registered\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-event-not-allowed"));
    }

    private static RequestPostProcessor capped(long userId, String role) {
        return jwt().jwt(token -> token.subject(Long.toString(userId))
                        .claim(AccessTokenService.TOKEN_ID_CLAIM, 1L)
                        .claim(AccessTokenService.SCOPE_CLAIM, "WRITE")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void unknownTokensAreRejected() throws Exception {
        mockMvc.perform(get(NOTES).with(bearer("pat_unknown"))).andExpect(status().isUnauthorized());
    }

    @Test
    void theDataExportListsTheTokensButNotTheirValues() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        String token = token(create(session, "script", "READ", 30, PASSWORD));

        String export = mockMvc.perform(get("/api/v1/users/me/export").with(bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.related.accessTokens[0].name").value("script"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(export).doesNotContain(token);
    }

    private ResultActions create(String bearer, String name, String scope, int days, String password) throws Exception {
        return mockMvc.perform(post(TOKENS)
                .with(bearer(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(
                        "{\"name\":\"%s\",\"scope\":\"%s\",\"expiresInDays\":%d,\"currentPassword\":\"%s\"}",
                        name, scope, days, password)));
    }

    private void revokeAll(String session) throws Exception {
        List<Integer> ids = JsonPath.read(
                mockMvc.perform(get(TOKENS).with(bearer(session)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$[*].id");
        for (int id : ids) {
            mockMvc.perform(delete(TOKENS + "/" + id).with(bearer(session)));
        }
    }

    private static String token(ResultActions created) throws Exception {
        return JsonPath.read(body(created), "$.token");
    }

    private static long id(ResultActions created) throws Exception {
        return ((Number) JsonPath.read(body(created), "$.details.id")).longValue();
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
