package com.hikmethankolay.template.social;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.social.dto.SocialAccountResponse;
import com.hikmethankolay.template.social.dto.UnlinkSocialAccountRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lists the caller's linked GitHub and Google accounts and unlinks the ones they no longer want. */
@RestController
@RequestMapping("/api/v1/users/me/social-accounts")
@Tag(name = "Authentication")
public class SocialAccountController {

    private final SocialAccountService socialAccounts;

    /**
     * Creates the controller.
     *
     * @param socialAccounts lists and unlinks provider accounts
     */
    public SocialAccountController(SocialAccountService socialAccounts) {
        this.socialAccounts = socialAccounts;
    }

    /**
     * Lists the caller's linked provider accounts.
     *
     * @param jwt the caller's token
     * @return the links, oldest first
     */
    @GetMapping
    @Operation(
            operationId = "listSocialAccounts",
            summary = "List your linked accounts",
            description = "The GitHub and Google accounts that can sign in to this one, oldest first.")
    @ApiResponse(
            responseCode = "200",
            description = "The linked accounts",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SocialAccountResponse.class))))
    public List<SocialAccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return socialAccounts.list(TokenService.userId(jwt));
    }

    /**
     * Unlinks one of the caller's provider accounts.
     *
     * @param jwt     the caller's token
     * @param id      the link to remove
     * @param request the caller's password
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "unlinkSocialAccount",
            summary = "Unlink an account",
            description = "Needs the password, so the account always keeps a way to sign in; an account created "
                    + "by a GitHub or Google login sets one with the password reset first. Signing in with that "
                    + "provider account later works like a first social login: it links it again if its verified "
                    + "email is this account's, or creates a new account otherwise. Wrong "
                    + "passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(responseCode = "204", description = "The account is unlinked")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input (see `errors`), or the password is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "404",
            description = "You have no linked account with this id",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void unlink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Valid @RequestBody UnlinkSocialAccountRequest request) {
        socialAccounts.unlink(TokenService.userId(jwt), id, request.currentPassword());
    }
}
