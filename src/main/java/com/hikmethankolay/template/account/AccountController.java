package com.hikmethankolay.template.account;

import com.hikmethankolay.template.account.dto.EmailRequest;
import com.hikmethankolay.template.account.dto.PasswordResetRequest;
import com.hikmethankolay.template.account.dto.TokenRequest;
import com.hikmethankolay.template.common.ApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public account endpoints under {@code /api/v1/auth}.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Success</th></tr>
 *   <tr><td>POST</td><td>/verify-email</td><td>204</td></tr>
 *   <tr><td>POST</td><td>/verify-email/resend</td><td>202 (always)</td></tr>
 *   <tr><td>POST</td><td>/password-reset</td><td>202 (always)</td></tr>
 *   <tr><td>POST</td><td>/password-reset/confirm</td><td>204</td></tr>
 * </table>
 *
 * <p>The two 202 endpoints answer the same way whether or not the address has an account.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Account")
public class AccountController {

    private static final String INVALID_TOKEN =
            "Invalid input (see `errors`), or the token is unknown, used or expired";

    private final AccountService accountService;

    /**
     * Creates the controller.
     *
     * @param accountService account business logic
     */
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Confirms an email address with the token from the verification email.
     *
     * @param request the token
     */
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Confirm an email address",
            description = "Completes a sign-up, creating the account, or confirms the new address of an account.")
    @ApiResponse(responseCode = "204", description = "Account created or email address verified")
    @ApiResponse(
            responseCode = "400",
            description = INVALID_TOKEN,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The sign-up's username or email address was taken since; sign up again",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        accountService.verifyEmail(request.token());
    }

    /**
     * Sends a new verification email.
     *
     * @param request the address to verify
     */
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Resend the verification email")
    @ApiResponse(
            responseCode = "202",
            description = "A new verification email is sent if the address belongs to an "
                    + "unverified account or to a sign-up waiting for its link; the answer is the same either way")
    public void resendVerification(@Valid @RequestBody EmailRequest request) {
        accountService.resendVerification(request.email());
    }

    /**
     * Sends a password reset email.
     *
     * @param request the account's address
     */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a password reset email")
    @ApiResponse(
            responseCode = "202",
            description = "A reset email is sent if the address belongs to an account; "
                    + "the answer is the same either way")
    public void requestPasswordReset(@Valid @RequestBody EmailRequest request) {
        accountService.requestPasswordReset(request.email());
    }

    /**
     * Sets a new password with the token from the reset email.
     *
     * @param request the token and the new password
     */
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password with a reset token")
    @ApiResponse(responseCode = "204", description = "Password changed; every session of the user has ended")
    @ApiResponse(
            responseCode = "400",
            description = INVALID_TOKEN,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        accountService.resetPassword(request.token(), request.newPassword());
    }
}
