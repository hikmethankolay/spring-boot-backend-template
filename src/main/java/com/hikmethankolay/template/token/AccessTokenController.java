package com.hikmethankolay.template.token;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.token.dto.AccessTokenResponse;
import com.hikmethankolay.template.token.dto.CreateAccessTokenRequest;
import com.hikmethankolay.template.token.dto.CreatedAccessTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets users create personal access tokens for scripts and integrations, list them and revoke them. These endpoints
 * need an interactive login: an access token cannot manage tokens ({@link AccessTokenPolicy}).
 */
@RestController
@RequestMapping("/api/v1/users/me/access-tokens")
@Tag(name = "Access tokens")
public class AccessTokenController {

    private final AccessTokenService accessTokens;

    /**
     * Creates the controller.
     *
     * @param accessTokens creates, lists and revokes tokens
     */
    public AccessTokenController(AccessTokenService accessTokens) {
        this.accessTokens = accessTokens;
    }

    /**
     * Lists the caller's unexpired tokens.
     *
     * @param jwt the caller's token
     * @return the tokens, newest first
     */
    @GetMapping
    @Operation(
            operationId = "listAccessTokens",
            summary = "List your access tokens",
            description = "Unexpired tokens, newest first. The token values are never shown again after creation.")
    @ApiResponse(
            responseCode = "200",
            description = "The tokens",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AccessTokenResponse.class))))
    public List<AccessTokenResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return accessTokens.list(TokenService.userId(jwt));
    }

    /**
     * Creates a token.
     *
     * @param jwt     the caller's token
     * @param request name, scope, lifetime and password
     * @return the new token, shown only this once
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            operationId = "createAccessToken",
            summary = "Create an access token",
            description = "Returns a token for `Authorization: Bearer pat_...`, shown only in this response. It acts "
                    + "as you, with your role (capped by the server's `app.access-tokens.max-role`) and the chosen "
                    + "scope, until it expires, you revoke it or your sessions end (password change, role change, "
                    + "suspension). You get an email about it.")
    @ApiResponse(
            responseCode = "201",
            description = "Token created",
            content = @Content(schema = @Schema(implementation = CreatedAccessTokenResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The password is wrong or the lifetime is longer than the server allows",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = "Access tokens are turned off on this server",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "You already have the most tokens allowed",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public CreatedAccessTokenResponse create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAccessTokenRequest request) {
        return accessTokens.create(TokenService.userId(jwt), request);
    }

    /**
     * Revokes one of the caller's tokens.
     *
     * @param jwt the caller's token
     * @param id  the token to revoke
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "revokeAccessToken",
            summary = "Revoke an access token",
            description = "The token stops working at once.")
    @ApiResponse(responseCode = "204", description = "The token is revoked")
    @ApiResponse(
            responseCode = "404",
            description = "You have no access token with this id",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        accessTokens.revoke(TokenService.userId(jwt), id);
    }
}
