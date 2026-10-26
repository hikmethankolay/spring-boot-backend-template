package com.hikmethankolay.template.social;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** Login with GitHub, with the calls to GitHub replaced by a mock of {@link SocialProfileClient}. */
class SocialLoginIntegrationTest extends IntegrationTest {

    /** A PKCE code verifier: 43 characters from the unreserved set (RFC 7636). */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @MockitoBean
    private SocialProfileClient profiles;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void configuredProvidersAreListedWithTheirAuthorizationUrl() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth2/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].provider", Matchers.contains("github")))
                .andExpect(jsonPath(
                        "$[0].authorizationUri",
                        Matchers.allOf(
                                Matchers.startsWith("https://github.com/login/oauth/authorize?"),
                                Matchers.containsString("client_id=test-client-id"),
                                Matchers.containsString("redirect_uri="))));
    }

    @Test
    void firstLoginCreatesAUserAndLaterLoginsReuseIt() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = "octo-" + subject.substring(0, 8) + "@example.com";
        givenProfile("code-1", new SocialProfile("github", subject, email, true, "octo cat!"));
        givenProfile("code-2", new SocialProfile("github", subject, "changed@example.com", true, "octo"));

        String first = loginWith("code-1")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(get("/api/v1/users/me").with(bearer(JsonPath.read(first, "$.accessToken"))))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.username", Matchers.startsWith("octocat")));

        // Linked by the provider's user id, not the email, which can change at the provider.
        String second = loginWith("code-2")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(get("/api/v1/users/me").with(bearer(JsonPath.read(second, "$.accessToken"))))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void anAccountWithAVerifiedEmailIsLinkedButAnUnverifiedOneIsNot() throws Exception {
        String verified = uniqueUsername();
        long verifiedId = register(verified);
        // Sign-ups are verified when the account is created; an unverified one changed its address.
        String unverified = uniqueUsername();
        jdbc.update("UPDATE users SET email_verified = false WHERE id = ?", register(unverified));
        givenProfile(
                "code-3",
                new SocialProfile("github", UUID.randomUUID().toString(), verified + "@example.com", true, "x"));
        givenProfile(
                "code-4",
                new SocialProfile("github", UUID.randomUUID().toString(), unverified + "@example.com", true, "y"));

        String body = loginWith("code-3")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(get("/api/v1/users/me").with(bearer(JsonPath.read(body, "$.accessToken"))))
                .andExpect(jsonPath("$.id").value(verifiedId));
        loginWith("code-4")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:social-account-conflict"));
    }

    @Test
    void loginWithoutAPkceVerifierIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth2/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"code-6"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("codeVerifier"));
        verifyNoInteractions(profiles);
    }

    @Test
    void loginsWithoutAVerifiedEmailOrWithABadCodeAreRefused() throws Exception {
        givenProfile("code-5", new SocialProfile("github", UUID.randomUUID().toString(), "x@example.com", false, "x"));
        when(profiles.fetch(any(), eq("bad-code"), any()))
                .thenThrow(new BadCredentialsException("The provider rejected the login."));

        loginWith("code-5")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:social-email-unverified"));
        loginWith("bad-code")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-credentials"));
        mockMvc.perform(post("/api/v1/auth/oauth2/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"code":"code-5","codeVerifier":"%s"}""", VERIFIER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:provider-not-configured"));
    }

    private void givenProfile(String code, SocialProfile profile) {
        when(profiles.fetch(any(), eq(code), any())).thenReturn(profile);
    }

    private ResultActions loginWith(String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/oauth2/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"code":"%s","codeVerifier":"%s"}""", code, VERIFIER)));
    }
}
