package com.hikmethankolay.template.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** With app.account.require-verified-email=true (the prod default), users with an unconfirmed address cannot log in. */
@TestPropertySource(properties = "app.account.require-verified-email=true")
class VerifiedEmailRequiredIntegrationTest extends IntegrationTest {

    @Test
    void userWithAnUnconfirmedNewAddressCannotLogInUntilVerified() throws Exception {
        // Sign-ups are verified when the account is created, so an unverified user is one whose address an
        // administrator changed (a user's own change takes effect only once confirmed).
        String username = uniqueUsername();
        long id = register(username);
        String newAddress = "moved-" + username + "@example.com";
        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .with(bearer(login(ADMIN_USERNAME, ADMIN_PASSWORD)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s"}""", newAddress)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Email address is not verified."));

        confirmEmail(newAddress);
        login(username, PASSWORD);
    }

    @Test
    void configuredAdminIsVerifiedFromTheStart() throws Exception {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }
}
