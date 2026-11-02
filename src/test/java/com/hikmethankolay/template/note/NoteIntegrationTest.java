package com.hikmethankolay.template.note;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class NoteIntegrationTest extends IntegrationTest {

    @Test
    void ownerCanCreateReadUpdateAndDeleteANote() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        long id = createNote(token, "Groceries", "milk");

        mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Groceries"))
                .andExpect(jsonPath("$.body").value("milk"));
        mockMvc.perform(put("/api/v1/notes/{id}", id)
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Groceries","body":"milk, eggs"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("milk, eggs"));
        mockMvc.perform(get("/api/v1/notes").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));

        mockMvc.perform(delete("/api/v1/notes/{id}", id).with(bearer(token))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:note-not-found"));
    }

    @Test
    void updateWithAnOutdatedETagIsRefused() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        long id = createNote(token, "Groceries", "milk");
        String etag = mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", Matchers.matchesPattern("\"\\d+\"")))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(token)).header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        String newEtag = mockMvc.perform(put("/api/v1/notes/{id}", id)
                        .with(bearer(token))
                        .header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Groceries","body":"milk, eggs"}"""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", Matchers.not(etag)))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        // A second client still holding the first tag does not overwrite the change.
        mockMvc.perform(put("/api/v1/notes/{id}", id)
                        .with(bearer(token))
                        .header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Groceries","body":"bread"}"""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("urn:problem:precondition-failed"));
        mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(token)))
                .andExpect(header().string("ETag", newEtag))
                .andExpect(jsonPath("$.body").value("milk, eggs"));
    }

    @Test
    void otherUsersNotesLookMissing() throws Exception {
        String owner = registerAndLogin(uniqueUsername());
        String other = registerAndLogin(uniqueUsername());
        long id = createNote(owner, "Private", "secret");

        mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(other))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/notes/{id}", id)
                        .with(bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Mine now","body":""}"""))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/notes/{id}", id).with(bearer(other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/notes").with(bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/api/v1/notes/{id}", id).with(bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Private"));
    }

    @Test
    void invalidInputAndSortAreRejected() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(post("/api/v1/notes")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  ","body":"x"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"));
        // body is not in the allow-list: Spring Data would sort by it, the API must not.
        mockMvc.perform(get("/api/v1/notes").param("sort", "body").with(bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-sort"));
        // Spring's own errors carry the documented generic type too.
        mockMvc.perform(get("/api/v1/notes/abc").with(bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("about:blank"));
        mockMvc.perform(post("/api/v1/notes")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("about:blank"));
    }

    @Test
    void notesNeedAToken() throws Exception {
        mockMvc.perform(get("/api/v1/notes")).andExpect(status().isUnauthorized());
    }

    @Test
    void notesAreInThePersonalDataExport() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        createNote(token, "Exported", "text");

        mockMvc.perform(get("/api/v1/users/me/export").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.related.notes[*].title", Matchers.contains("Exported")));
    }

    private long createNote(String token, String title, String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/notes")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"title":"%s","body":"%s"}""", title, body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.matchesPattern(".*/api/v1/notes/\\d+")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
