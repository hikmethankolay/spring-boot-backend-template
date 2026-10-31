package com.hikmethankolay.template.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SuspensionIntegrationTest extends IntegrationTest {

    private static final String SUSPENSION = "/api/v1/users/{id}/suspension";

    @Test
    void aSuspendedUserLosesTheirSessionsAndCannotLogInUntilReactivated() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        Tokens session = loginTokens(username, PASSWORD);
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(put(SUSPENSION, id).with(bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendedAt").isNotEmpty());

        // Every session ends at once.
        mockMvc.perform(get("/api/v1/users/me").with(bearer(session.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", session.refreshToken())))
                .andExpect(status().isUnauthorized());
        // The right password is refused; a wrong one gets the usual answer, so the suspension is not revealed.
        loginAs(username, PASSWORD, "10.0.41.1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:account-suspended"));
        loginAs(username, "Wrong#123", "10.0.41.1").andExpect(status().isUnauthorized());

        mockMvc.perform(delete(SUSPENSION, id).with(bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendedAt").doesNotExist());
        login(username, PASSWORD);
    }

    @Test
    void onlyTheOwnerSuspendsAdministratorsAndNobodyTheOwnerOrThemselves() throws Exception {
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long ownerId = ((Number) JsonPath.read(
                        mockMvc.perform(get("/api/v1/users/me").with(bearer(owner)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"))
                .longValue();
        String adminName = uniqueUsername();
        long adminId = promote(owner, register(adminName));
        long otherAdminId = promote(owner, register(uniqueUsername()));
        long userId = register(uniqueUsername());
        String admin = login(adminName, PASSWORD);

        mockMvc.perform(put(SUSPENSION, otherAdminId).with(bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:owner-required"));
        mockMvc.perform(put(SUSPENSION, ownerId).with(bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:owner-protected"));
        mockMvc.perform(put(SUSPENSION, adminId).with(bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:own-account"));
        mockMvc.perform(put(SUSPENSION, userId).with(bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(put(SUSPENSION, otherAdminId).with(bearer(owner))).andExpect(status().isOk());

        String user = registerAndLogin(uniqueUsername());
        mockMvc.perform(put(SUSPENSION, userId).with(bearer(user))).andExpect(status().isForbidden());
    }

    private long promote(String owner, long id) throws Exception {
        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .with(bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"role":"ADMIN","currentPassword":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isOk());
        return id;
    }

    private ResultActions loginAs(String username, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"identifier":"%s","password":"%s"}""", username, password)));
    }
}
