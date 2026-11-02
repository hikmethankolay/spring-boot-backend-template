package com.hikmethankolay.template.auth;

import com.hikmethankolay.template.auth.dto.SessionResponse;
import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lists the caller's sessions and ends the ones they don't recognise. */
@RestController
@RequestMapping("/api/v1/users/me/sessions")
@Tag(name = "Authentication")
public class SessionController {

    private final RefreshTokenService refreshTokens;

    /**
     * Creates the controller.
     *
     * @param refreshTokens lists and ends sessions
     */
    public SessionController(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /**
     * Lists the caller's active sessions.
     *
     * @param jwt the caller's token
     * @return the sessions, newest first
     */
    @GetMapping
    @Operation(
            operationId = "listSessions",
            summary = "List your sessions",
            description = "One entry per login that has not ended, newest first, with the browser or app and the IP "
                    + "of its login or last refresh. `current` marks the session making this request.")
    @ApiResponse(
            responseCode = "200",
            description = "The active sessions",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SessionResponse.class))))
    public List<SessionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        UUID current = TokenService.sessionId(jwt);
        return refreshTokens.activeSessions(TokenService.userId(jwt)).stream()
                .map(session -> new SessionResponse(
                        session.id(),
                        session.id().equals(current),
                        session.rememberMe(),
                        session.startedAt(),
                        session.lastUsedAt(),
                        session.expiresAt(),
                        session.userAgent(),
                        session.ipAddress()))
                .toList();
    }

    /**
     * Ends every session of the caller except the one making the request.
     *
     * @param jwt the caller's token
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "revokeOtherSessions",
            summary = "Sign out all other sessions",
            description = "Logs every other device out: their refresh tokens stop working at once and their access "
                    + "tokens are rejected. The session making this request stays signed in.")
    @ApiResponse(responseCode = "204", description = "Every other session has ended")
    public void revokeOthers(@AuthenticationPrincipal Jwt jwt) {
        refreshTokens.revokeOthers(TokenService.userId(jwt), TokenService.sessionId(jwt));
    }

    /**
     * Ends one of the caller's sessions.
     *
     * @param jwt the caller's token
     * @param id  the session to end
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "revokeSession",
            summary = "End a session",
            description = "Logs that device out: its refresh token stops working at once and its access token is "
                    + "rejected. Ending the current session works like logging out.")
    @ApiResponse(responseCode = "204", description = "The session has ended")
    @ApiResponse(
            responseCode = "404",
            description = "You have no session with this id",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        refreshTokens.revoke(TokenService.userId(jwt), id);
    }
}
