package com.hikmethankolay.template.mfa;

import com.hikmethankolay.template.auth.RefreshTokenService;
import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.mfa.dto.ConfirmTotpRequest;
import com.hikmethankolay.template.mfa.dto.DisableTotpRequest;
import com.hikmethankolay.template.mfa.dto.RecoveryCodesResponse;
import com.hikmethankolay.template.mfa.dto.RegenerateRecoveryCodesRequest;
import com.hikmethankolay.template.mfa.dto.TotpEnrollmentResponse;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.token.AccessTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-factor authentication of the current user, under {@code /api/v1/users/me/mfa/totp}.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Access</th><th>Success</th></tr>
 *   <tr><td>POST</td><td>/</td><td>authenticated</td><td>200 secret + otpauth URI</td></tr>
 *   <tr><td>POST</td><td>/confirm</td><td>authenticated</td><td>200 recovery codes</td></tr>
 *   <tr><td>POST</td><td>/recovery-codes</td><td>authenticated + password + code</td><td>200 recovery codes</td></tr>
 *   <tr><td>DELETE</td><td>/</td><td>authenticated + password + code</td><td>204</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/users/me/mfa/totp")
@Tag(name = "Two-factor authentication")
public class MfaController {

    private static final String CONFLICT = "Two-factor authentication is already on (enroll, confirm), or it is off "
            + "or no enrollment is pending (confirm, disable)";

    private final MfaService mfaService;

    private final RefreshTokenService refreshTokens;

    private final AccessTokenService accessTokens;

    /**
     * Creates the controller.
     *
     * @param mfaService    two-factor authentication logic
     * @param refreshTokens ends the caller's other sessions once two-factor authentication is on
     * @param accessTokens  revokes personal access tokens once two-factor authentication is on
     */
    public MfaController(MfaService mfaService, RefreshTokenService refreshTokens, AccessTokenService accessTokens) {
        this.mfaService = mfaService;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
    }

    /**
     * Starts enrolling an authenticator app.
     *
     * @param jwt the caller's token
     * @return the new secret
     */
    @PostMapping
    @Operation(
            operationId = "enrollTotp",
            summary = "Start setting up an authenticator app",
            description = "Returns a new secret and its `otpauth://` URI: show the URI as a QR code. Two-factor "
                    + "authentication stays off until the first code is confirmed; calling this again replaces the "
                    + "pending secret.")
    @ApiResponse(
            responseCode = "200",
            description = "The new secret",
            content = @Content(schema = @Schema(implementation = TotpEnrollmentResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = CONFLICT,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public TotpEnrollmentResponse enroll(@AuthenticationPrincipal Jwt jwt) {
        return mfaService.enroll(TokenService.userId(jwt));
    }

    /**
     * Turns two-factor authentication on with the first code from the app.
     *
     * @param jwt     the caller's token
     * @param request the code
     * @return the recovery codes, shown only this once
     */
    @PostMapping("/confirm")
    @Operation(
            operationId = "confirmTotp",
            summary = "Turn on two-factor authentication",
            description = "Checks the password and a code from the app and turns two-factor authentication on. "
                    + "Returns ten single-use recovery codes; they cannot be shown again."
                    + " Wrong passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(
            responseCode = "200",
            description = "Two-factor authentication is on",
            content = @Content(schema = @Schema(implementation = RecoveryCodesResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input (see `errors`), or the password or the code is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = CONFLICT,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public RecoveryCodesResponse confirm(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmTotpRequest request) {
        long userId = TokenService.userId(jwt);
        RecoveryCodesResponse codes = mfaService.confirm(userId, request.currentPassword(), request.code());
        // Sessions and tokens made with the password alone would otherwise keep working around the new factor.
        refreshTokens.revokeOthers(userId, TokenService.sessionId(jwt));
        accessTokens.revokeAll(userId);
        return codes;
    }

    /**
     * Replaces the recovery codes.
     *
     * @param jwt         the caller's token
     * @param request     password and an authenticator code
     * @param httpRequest the servlet request, used for the client IP
     * @return the new recovery codes, shown only this once
     */
    @PostMapping("/recovery-codes")
    @Operation(
            operationId = "regenerateRecoveryCodes",
            summary = "Create new recovery codes",
            description = "Needs the password and a code from the app (not a recovery code). Replaces every recovery "
                    + "code, used or not, with ten new ones; they cannot be shown again. Wrong passwords and codes "
                    + "count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(
            responseCode = "200",
            description = "The new recovery codes; the old ones no longer work",
            content = @Content(schema = @Schema(implementation = RecoveryCodesResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input (see `errors`), or the password or the code is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "Two-factor authentication is off",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public RecoveryCodesResponse regenerateRecoveryCodes(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegenerateRecoveryCodesRequest request,
            HttpServletRequest httpRequest) {
        return mfaService.regenerateRecoveryCodes(
                TokenService.userId(jwt), request.currentPassword(), request.code(), httpRequest.getRemoteAddr());
    }

    /**
     * Turns two-factor authentication off.
     *
     * @param jwt         the caller's token
     * @param request     password and a code
     * @param httpRequest the servlet request, used for the client IP
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "disableTotp",
            summary = "Turn off two-factor authentication",
            description = "Needs the password and a code from the app or an unused recovery code. Deletes the "
                    + "secret and the recovery codes. Wrong passwords and codes count as failed logins: repeated "
                    + "failures lock the IP and the account (429).")
    @ApiResponse(responseCode = "204", description = "Two-factor authentication is off")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input (see `errors`), or the password or the code is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = CONFLICT,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void disable(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DisableTotpRequest request,
            HttpServletRequest httpRequest) {
        mfaService.disable(
                TokenService.userId(jwt), request.currentPassword(), request.code(), httpRequest.getRemoteAddr());
    }
}
