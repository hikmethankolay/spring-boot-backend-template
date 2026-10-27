package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.mail.dto.EmailPreferenceResponse;
import com.hikmethankolay.template.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's choice of optional emails, for a settings page. Transactional emails (account, security) are not
 * listed and cannot be turned off.
 */
@RestController
@RequestMapping("/api/v1/users/me/email-preferences")
@Tag(name = "Email")
public class EmailPreferenceController {

    private final EmailPreferenceService preferences;

    /**
     * Creates the controller.
     *
     * @param preferences email preference logic
     */
    public EmailPreferenceController(EmailPreferenceService preferences) {
        this.preferences = preferences;
    }

    /**
     * Lists the optional email categories and whether the caller receives them.
     *
     * @param jwt the caller's token
     * @return every optional category
     */
    @GetMapping
    @Operation(summary = "List the caller's email preferences")
    @ApiResponse(
            responseCode = "200",
            description = "Every optional category",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EmailPreferenceResponse.class))))
    public List<EmailPreferenceResponse> listEmailPreferences(@AuthenticationPrincipal Jwt jwt) {
        return preferences.preferences(TokenService.userId(jwt));
    }

    /**
     * Turns optional email categories on or off; categories left out keep their setting.
     *
     * @param jwt     the caller's token
     * @param choices category key to whether it should be on
     * @return every optional category after the change
     */
    @PutMapping
    @Operation(summary = "Change the caller's email preferences")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Category key to `true` (on) or `false` (off)",
            content =
                    @Content(
                            schema = @Schema(type = "object", additionalPropertiesSchema = Boolean.class),
                            examples = @ExampleObject(value = "{\"product-updates\": false}")))
    @ApiResponse(
            responseCode = "200",
            description = "Every optional category after the change",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EmailPreferenceResponse.class))))
    @ApiResponse(
            responseCode = "400",
            description = "A key is not an optional category (transactional emails cannot be turned off), "
                    + "or a value is not `true` or `false`",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public List<EmailPreferenceResponse> updateEmailPreferences(
            @AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Boolean> choices) {
        return preferences.update(TokenService.userId(jwt), choices);
    }
}
