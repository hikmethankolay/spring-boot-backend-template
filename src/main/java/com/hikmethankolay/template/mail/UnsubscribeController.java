package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.mail.dto.UnsubscribeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unsubscribe link of optional emails. No login is needed: the signed token in the link says who and what. The
 * {@code List-Unsubscribe} header of each optional email points to {@code POST /api/v1/mail/unsubscribe}, which mail
 * clients call directly (RFC 8058 one-click); the footer link opens the frontend's {@code /unsubscribe} page,
 * which shows {@code GET} and confirms with {@code POST}.
 */
@RestController
@RequestMapping("/api/v1/mail/unsubscribe")
@Tag(name = "Email")
public class UnsubscribeController {

    private static final String INVALID_TOKEN = "The token is malformed or was not issued by this server";

    private static final String TOKEN = "Token from the unsubscribe link";

    private final EmailPreferenceService preferences;

    /**
     * Creates the controller.
     *
     * @param preferences email preference logic
     */
    public UnsubscribeController(EmailPreferenceService preferences) {
        this.preferences = preferences;
    }

    /**
     * Tells what an unsubscribe link turns off. Changes nothing, since mail scanners open links.
     *
     * @param token the token from the link
     * @return the category and whether it is still on
     */
    @GetMapping
    @Operation(summary = "Show what an unsubscribe link turns off", description = "Changes nothing.")
    @ApiResponse(responseCode = "200", description = "The category of the link")
    @ApiResponse(
            responseCode = "400",
            description = INVALID_TOKEN,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public UnsubscribeResponse unsubscribeStatus(@Parameter(description = TOKEN) @RequestParam String token) {
        return preferences.unsubscribeStatus(token);
    }

    /**
     * Turns off the category of an unsubscribe link. Mail clients send {@code List-Unsubscribe=One-Click} as a form
     * body (RFC 8058), without cookies or other headers; the frontend's unsubscribe page sends no body.
     *
     * @param token    the token from the link
     * @param oneClick {@code One-Click} when a mail client sends the request
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Unsubscribe from one category",
            description = "Target of the `List-Unsubscribe` header (RFC 8058 one-click) and of the unsubscribe page. "
                    + "Using a link again, or one of a deleted account, succeeds without effect.")
    @ApiResponse(responseCode = "204", description = "The category is off")
    @ApiResponse(
            responseCode = "400",
            description = INVALID_TOKEN,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void unsubscribe(
            @Parameter(description = TOKEN) @RequestParam String token,
            @Parameter(description = "`One-Click` from a mail client (form body)")
                    @RequestParam(name = "List-Unsubscribe", required = false)
                    String oneClick) {
        preferences.unsubscribe(token, "One-Click".equals(oneClick));
    }

    /**
     * Turns off every optional category for the user of an unsubscribe link.
     *
     * @param token the token from the link
     */
    @PostMapping("/all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unsubscribe from every optional category")
    @ApiResponse(responseCode = "204", description = "Every optional category is off")
    @ApiResponse(
            responseCode = "400",
            description = INVALID_TOKEN,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void unsubscribeAll(@Parameter(description = TOKEN) @RequestParam String token) {
        preferences.unsubscribeAll(token);
    }
}
