package com.hikmethankolay.template.note;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.common.SortableBy;
import com.hikmethankolay.template.note.dto.NoteRequest;
import com.hikmethankolay.template.note.dto.NoteResponse;
import com.hikmethankolay.template.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The {@code /api/v1/notes} resource: the caller's own notes. Any authenticated user can use it; nobody,
 * administrators included, can reach another user's notes through it.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Access</th><th>Success</th></tr>
 *   <tr><td>GET</td><td>/</td><td>authenticated</td><td>200 (paginated)</td></tr>
 *   <tr><td>POST</td><td>/</td><td>authenticated</td><td>201 + Location</td></tr>
 *   <tr><td>GET</td><td>/{id}</td><td>owner</td><td>200</td></tr>
 *   <tr><td>PUT</td><td>/{id}</td><td>owner</td><td>200</td></tr>
 *   <tr><td>DELETE</td><td>/{id}</td><td>owner</td><td>204</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/notes")
@Tag(name = "Notes")
public class NoteController {

    private static final String NOT_FOUND = "The caller has no note with this id";

    private final NoteService noteService;

    /**
     * Creates the controller.
     *
     * @param noteService note business logic
     */
    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    /**
     * Lists the caller's notes.
     *
     * @param jwt      the caller's token
     * @param pageable page, size and sort order
     * @return one page of notes
     */
    @GetMapping
    @Operation(operationId = "listNotes", summary = "List the current user's notes")
    @ApiResponse(responseCode = "200", description = "One page of notes")
    @SortableBy(NoteService.SORTABLE_PROPERTIES)
    public Page<NoteResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return noteService.list(TokenService.userId(jwt), pageable);
    }

    /**
     * Creates a note.
     *
     * @param jwt     the caller's token
     * @param request title and text
     * @return {@code 201 Created} with the note and a {@code Location} header pointing to it
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createNote", summary = "Create a note")
    @ApiResponse(
            responseCode = "201",
            description = "Note created",
            headers =
                    @Header(
                            name = HttpHeaders.LOCATION,
                            description = "URL of the new note",
                            schema = @Schema(type = "string", format = "uri")),
            content = @Content(schema = @Schema(implementation = NoteResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "You already have the most notes allowed, or a request with the same `Idempotency-Key` is "
                    + "still running",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public ResponseEntity<NoteResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NoteRequest request) {
        NoteResponse note = noteService.create(TokenService.userId(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(note.id())
                .toUri();
        return ResponseEntity.created(location).body(note);
    }

    /**
     * Returns one of the caller's notes.
     *
     * @param jwt the caller's token
     * @param id  note id
     * @return the note, with its version as {@code ETag}
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getNote", summary = "Get a note")
    @ApiResponse(
            responseCode = "200",
            description = "The note",
            headers = @Header(name = HttpHeaders.ETAG, description = ApiDocs.ETAG, schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = NoteResponse.class)))
    @ApiResponse(responseCode = "304", description = ApiDocs.NOT_MODIFIED, content = @Content)
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public ResponseEntity<NoteResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return noteService.get(TokenService.userId(jwt), id).toResponse();
    }

    /**
     * Replaces the title and text of one of the caller's notes.
     *
     * @param jwt     the caller's token
     * @param id      note id
     * @param ifMatch the {@code ETag} the change is based on, if the client sent one
     * @param request new title and text
     * @return the updated note, with its new version as {@code ETag}
     */
    @PutMapping("/{id}")
    @Operation(operationId = "updateNote", summary = "Replace a note's title and text")
    @ApiResponse(
            responseCode = "200",
            description = "The updated note",
            headers = @Header(name = HttpHeaders.ETAG, description = ApiDocs.ETAG, schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = NoteResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The note was changed at the same time; reload it and retry",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "412",
            description = ApiDocs.PRECONDITION_FAILED,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public ResponseEntity<NoteResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Parameter(description = ApiDocs.IF_MATCH) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    String ifMatch,
            @Valid @RequestBody NoteRequest request) {
        return noteService
                .update(TokenService.userId(jwt), id, request, ifMatch)
                .toResponse();
    }

    /**
     * Deletes one of the caller's notes.
     *
     * @param jwt the caller's token
     * @param id  note id
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteNote", summary = "Delete a note")
    @ApiResponse(responseCode = "204", description = "Note deleted")
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        noteService.delete(TokenService.userId(jwt), id);
    }
}
