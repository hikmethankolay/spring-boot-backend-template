package com.hikmethankolay.template.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** State-changing requests authenticated by cookie must come from an allowed origin ({@link CookieCsrfFilter}). */
class CsrfGuardIntegrationTest extends IntegrationTest {

    @Test
    void aCookieRequestWithoutOriginOrHeaderIsRejected() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(createNote().cookie(new Cookie("auth_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:cross-site-request"));
        mockMvc.perform(createNote()
                        .cookie(new Cookie("auth_token", token))
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuthorizationHeaderOtherThanBearerDoesNotSkipTheGuard() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        // A browser adds Basic credentials by itself behind a password-protected proxy; the cookie still authenticates.
        mockMvc.perform(createNote()
                        .cookie(new Cookie("auth_token", token))
                        .header(HttpHeaders.AUTHORIZATION, "Basic c3RhZ2luZzpzZWNyZXQ="))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:cross-site-request"));
        mockMvc.perform(createNote()
                        .cookie(new Cookie("auth_token", token))
                        .header(HttpHeaders.AUTHORIZATION, "bearer " + token))
                .andExpect(status().isCreated());
    }

    @Test
    void aCookieRequestFromAnAllowedOriginOrWithTheHeaderPasses() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(createNote()
                        .cookie(new Cookie("auth_token", token))
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .andExpect(status().isCreated());
        // The API's own origin, e.g. a frontend served from the same host.
        mockMvc.perform(createNote()
                        .cookie(new Cookie("auth_token", token))
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isCreated());
        mockMvc.perform(createNote()
                        .cookie(new Cookie("auth_token", token))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isCreated());
    }

    @Test
    void bearerRequestsAndSafeMethodsAreNotAffected() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(createNote().with(bearer(token))).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/notes").cookie(new Cookie("auth_token", token)))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder createNote() {
        return post("/api/v1/notes").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"CSRF","body":"guarded"}""");
    }
}
