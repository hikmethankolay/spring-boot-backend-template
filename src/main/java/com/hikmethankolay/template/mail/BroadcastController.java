package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.mail.dto.BroadcastPreview;
import com.hikmethankolay.template.mail.dto.BroadcastRequest;
import com.hikmethankolay.template.mail.dto.BroadcastResponse;
import com.hikmethankolay.template.mail.dto.BroadcastResult;
import com.hikmethankolay.template.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's announcements to many users. Only the owner may use it, only optional categories can be broadcast,
 * and sending needs the owner's password; preview first.
 */
@RestController
@RequestMapping("/api/v1/admin/mail/broadcasts")
@Tag(name = "Email")
public class BroadcastController {

    private static final String NOT_OWNER = "The caller is not the owner";

    private static final String INVALID = "Invalid input (see `errors`), or the category is not an optional one";

    private final BroadcastService broadcasts;

    /**
     * Creates the controller.
     *
     * @param broadcasts broadcast logic
     */
    public BroadcastController(BroadcastService broadcasts) {
        this.broadcasts = broadcasts;
    }

    /**
     * Shows how many users a broadcast would reach and how it reads, without sending anything.
     *
     * @param request the broadcast; the password is not needed
     * @return the recipient count and the rendered text
     */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Preview a broadcast", description = "Sends nothing.")
    @ApiResponse(responseCode = "200", description = "Recipients and the rendered text")
    @ApiResponse(
            responseCode = "400",
            description = INVALID,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = NOT_OWNER,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public BroadcastPreview previewBroadcast(@Valid @RequestBody BroadcastRequest request) {
        return broadcasts.preview(request);
    }

    /**
     * Records a broadcast and queues it, in the background, for every user in its audience who did not turn its
     * category off.
     *
     * @param jwt     the owner's token
     * @param request the broadcast, with the owner's current password
     * @return the broadcast id and the number of recipients expected
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('OWNER')")
    @Operation(
            summary = "Send a broadcast",
            description = "Records the broadcast and queues its emails in the background; the outbox sends them over "
                    + "the following minutes, after any other email. The history shows the final counts once "
                    + "`finishedAt` is set. One broadcast at a time, at most one per minute.")
    @ApiResponse(responseCode = "202", description = "Accepted; queuing has started")
    @ApiResponse(
            responseCode = "400",
            description = INVALID + ", or the current password is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = NOT_OWNER,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "Another broadcast is still being queued or started less than a minute ago",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public BroadcastResult sendBroadcast(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BroadcastRequest request) {
        return broadcasts.send(TokenService.userId(jwt), request);
    }

    /**
     * Lists the latest broadcasts.
     *
     * @return up to 100 broadcasts, newest first
     */
    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "List broadcasts")
    @ApiResponse(
            responseCode = "200",
            description = "Up to 100 broadcasts, newest first",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BroadcastResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = NOT_OWNER,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public List<BroadcastResponse> listBroadcasts() {
        return broadcasts.history();
    }
}
