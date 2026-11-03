package com.hikmethankolay.template.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** A JSON body over 1 MiB is refused while it is read, before it is held in memory and validated. */
class RequestBodyLimitIntegrationTest extends IntegrationTest {

    @Test
    void anOversizedJsonBodyIsABadRequest() throws Exception {
        // Valid apart from its size (2 MiB of whitespace), so only the length limit can make it a 400, not a 401.
        String padding = " ".repeat(2 * 1024 * 1024);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"nobody\"," + padding + "\"password\":\"Wrong#123\"}"))
                .andExpect(status().isBadRequest());
    }
}
