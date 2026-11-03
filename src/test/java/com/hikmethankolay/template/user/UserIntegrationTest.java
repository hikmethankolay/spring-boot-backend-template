package com.hikmethankolay.template.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class UserIntegrationTest extends IntegrationTest {

    @Test
    void meReturnsCurrentUser() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void updateProfileChangesOnlyProvidedFields() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"new-%s@example.com","currentPassword":"%s"}""", username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                // The new address waits for the link sent to it.
                .andExpect(jsonPath("$.email").value(username + "@example.com"))
                .andExpect(jsonPath("$.pendingEmail").value("new-" + username + "@example.com"));
    }

    @Test
    void updateProfileRejectsTakenUsername() throws Exception {
        String taken = uniqueUsername();
        register(taken);
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s"}""", taken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:username-taken"));
    }

    @Test
    void changePasswordRequiresCurrentPassword() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        String newPassword = "Changed#456-passphrase";

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .with(fromIp("10.0.20.2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"Wrong#123","newPassword":"%s"}""", newPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"))
                .andExpect(jsonPath("$.detail").value("Current password is incorrect."));

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","newPassword":"%s"}""", PASSWORD, newPassword)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.1.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isUnauthorized());
        login(username, newPassword);
    }

    @Test
    void guessingThePasswordWithAStolenTokenRunsIntoTheLockout() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        // The test profile blocks an IP after 3 failures, as for logins.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(changePassword(token, "Wrong#123", "10.0.20.6")).andExpect(status().isBadRequest());
        }
        mockMvc.perform(changePassword(token, PASSWORD, "10.0.20.6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("urn:problem:login-locked"));
        // Blocked before the check, so the password did not change.
        login(username, PASSWORD);
    }

    private static MockHttpServletRequestBuilder changePassword(String token, String currentPassword, String ip) {
        return put("/api/v1/users/me/password")
                .with(bearer(token))
                .with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"currentPassword":"%s","newPassword":"Changed#456-passphrase"}""", currentPassword));
    }

    @Test
    void userCanReadOwnRecordButNotOthers() throws Exception {
        long otherId = register(uniqueUsername());
        String username = uniqueUsername();
        long ownId = register(username);
        String token = login(username, PASSWORD);

        mockMvc.perform(get("/api/v1/users/{id}", ownId).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
        mockMvc.perform(get("/api/v1/users/{id}", otherId).with(bearer(token))).andExpect(status().isForbidden());
    }

    @Test
    void regularUserCannotUseAdminEndpoints() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(get("/api/v1/users").with(bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(delete("/api/v1/users/{id}", 1L).with(bearer(token))).andExpect(status().isForbidden());
    }

    @Test
    void regularUserCannotSetAnotherUsersPassword() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        String victim = uniqueUsername();
        long victimId = register(victim);

        mockMvc.perform(patch("/api/v1/users/{id}", victimId)
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"Taken#over-passphrase"}"""))
                .andExpect(status().isForbidden());

        login(victim, PASSWORD);
    }

    @Test
    void adminCanManageUsers() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long userId = register(uniqueUsername());

        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").isNumber());

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"role":"ADMIN","currentPassword":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Deleting an administrator needs the owner's password.
        mockMvc.perform(delete("/api/v1/users/{id}", userId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", userId).with(bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheOwnerManagesAdministratorsAndNobodyRemovesTheOwner() throws Exception {
        String ownerToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long ownerId = ((Number) JsonPath.read(
                        mockMvc.perform(get("/api/v1/users/me").with(bearer(ownerToken)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"))
                .longValue();
        String adminName = uniqueUsername();
        long adminId = register(adminName);
        long otherAdminId = register(uniqueUsername());
        long userId = register(uniqueUsername());

        // Promoting needs the owner's password.
        mockMvc.perform(patch("/api/v1/users/{id}", adminId)
                        .with(bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        for (long id : new long[] {adminId, otherAdminId}) {
            mockMvc.perform(patch("/api/v1/users/{id}", id)
                            .with(bearer(ownerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("""
                                    {"role":"ADMIN","currentPassword":"%s"}""", ADMIN_PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }
        String adminToken = login(adminName, PASSWORD);

        // An administrator manages regular users, but not roles, other administrators or the owner.
        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"Reset#1234-passphrase"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"role":"ADMIN","currentPassword":"%s"}""", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:owner-required"));
        mockMvc.perform(patch("/api/v1/users/{id}", ownerId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"Taken#over1"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/users/{id}", otherAdminId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", PASSWORD)))
                .andExpect(status().isForbidden());

        // Nobody deletes or demotes the owner, not even the owner.
        mockMvc.perform(delete("/api/v1/users/{id}", ownerId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:owner-protected"));
        mockMvc.perform(patch("/api/v1/users/{id}", ownerId)
                        .with(bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"role":"USER","currentPassword":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:own-account"));
        mockMvc.perform(patch("/api/v1/users/{id}", ownerId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"USER"}"""))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/users/me")
                        .with(bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isConflict());

        // The owner deletes an administrator after confirming the password.
        mockMvc.perform(delete("/api/v1/users/{id}", otherAdminId).with(bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        mockMvc.perform(delete("/api/v1/users/{id}", otherAdminId)
                        .with(bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", ADMIN_PASSWORD)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me").with(bearer(ownerToken)))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void adminCannotChangeOwnAccountThroughTheAdminEndpoint() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long adminId = ((Number) JsonPath.read(
                        mockMvc.perform(get("/api/v1/users/me").with(bearer(adminToken)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"))
                .longValue();

        // Without the current password /users/me would ask for, a stolen access token could take the account over.
        mockMvc.perform(patch("/api/v1/users/{id}", adminId)
                        .with(bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"Taken#over-passphrase"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:own-account"));

        login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    @Test
    void adminCannotDeleteOwnAccount() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String me = mockMvc.perform(get("/api/v1/users/me").with(bearer(adminToken)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long adminId = ((Number) JsonPath.read(me, "$.id")).longValue();

        mockMvc.perform(delete("/api/v1/users/{id}", adminId).with(bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanSearchUsersByTextRoleAndSuspension() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String active = uniqueUsername();
        String suspended = uniqueUsername();
        register(active);
        long suspendedId = register(suspended);
        mockMvc.perform(put("/api/v1/users/{id}/suspension", suspendedId).with(bearer(adminToken)))
                .andExpect(status().isOk());
        // "user_1a2b3c4d" -> "R_1A2B3C": any case, and "_" is a literal underscore.
        String part = active.substring(3, 10).toUpperCase(java.util.Locale.ROOT);

        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("q", part))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value(active));
        // Matches the email too.
        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("q", suspended + "@example"))
                .andExpect(jsonPath("$.content[*].username").value(org.hamcrest.Matchers.contains(suspended)));
        // "%" is not a wildcard: "user%<suffix>" matches nobody.
        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("q", "user%" + active.substring(5)))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get("/api/v1/users")
                        .with(bearer(adminToken))
                        .param("q", active)
                        .param("role", "ADMIN"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get("/api/v1/users")
                        .with(bearer(adminToken))
                        .param("q", active)
                        .param("role", "USER")
                        .param("suspended", "false"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/api/v1/users")
                        .with(bearer(adminToken))
                        .param("suspended", "true")
                        .param("size", "100"))
                .andExpect(jsonPath("$.content[*].username").value(org.hamcrest.Matchers.hasItem(suspended)))
                .andExpect(jsonPath("$.content[*].username")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(active))));
        // The search text is capped at the length of the longest email address.
        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("q", "x".repeat(255)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("role", "NOBODY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileUpdateWithAnOutdatedETagIsRefused() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        String etag = mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());

        String newEtag = mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s"}""", username + "x")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"username":"%s"}""", username + "y")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("urn:problem:precondition-failed"));
        // GET /me reads the tag from the database, never from the user cache.
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(header().string(HttpHeaders.ETAG, newEtag))
                .andExpect(jsonPath("$.username").value(username + "x"));
    }

    @Test
    void unknownSortPropertyIsBadRequest() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("sort", "passwordHashX"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sortingByAHiddenColumnIsRejected() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // passwordHash is a real entity property, but sorting by it would reveal the order of the hashes.
        mockMvc.perform(get("/api/v1/users").with(bearer(adminToken)).param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Cannot sort by 'passwordHash'. Allowed properties: "
                                + "id, username, email, role, emailVerified, createdAt, updatedAt."));
        mockMvc.perform(get("/api/v1/users")
                        .with(bearer(adminToken))
                        .param("sort", "createdAt,desc")
                        .param("sort", "passwordHash"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sortingByVisibleFieldsWorks() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/users")
                        .with(bearer(adminToken))
                        .param("sort", "createdAt,desc")
                        .param("sort", "username"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void deletingOwnAccountRequiresThePasswordAndEndsTheSession() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(bearer(token))
                        .with(fromIp("10.0.20.3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"Wrong#123"}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"password":"%s"}""", PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.3.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportContainsProfileAndSessions() throws Exception {
        String username = uniqueUsername();
        register(username);
        loginTokens(username, PASSWORD);
        String token = login(username, PASSWORD);

        mockMvc.perform(get("/api/v1/users/me/export").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"personal-data.json\""))
                .andExpect(jsonPath("$.exportedAt").exists())
                .andExpect(jsonPath("$.profile.username").value(username))
                .andExpect(jsonPath("$.profile.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.related.sessions.length()").value(2))
                .andExpect(jsonPath("$.related.sessions[0].id").exists())
                .andExpect(jsonPath("$.related.sessions[0].expiresAt").exists());
    }
}
