package com.hikmethankolay.template.user;

import com.hikmethankolay.template.audit.SecurityEvents;
import com.hikmethankolay.template.audit.dto.SecurityEventResponse;
import com.hikmethankolay.template.common.SortableBy;
import com.hikmethankolay.template.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's security activity from the audit log: logins, failed logins, password and email changes, sessions ended,
 * two-factor changes and administrators' changes to the account.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Access</th><th>Success</th></tr>
 *   <tr><td>GET</td><td>/me/security-events</td><td>authenticated</td><td>200 (paginated)</td></tr>
 *   <tr><td>GET</td><td>/{id}/security-events</td><td>ADMIN or the user</td><td>200 (paginated)</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
public class SecurityEventController {

    private static final String DESCRIPTION = "Newest first. Each event has its time, its name (e.g. "
            + "`auth.login_succeeded`, `auth.login_failed`, `user.password_changed`; the Security guide lists them), "
            + "the client IP and the id of the signed-in user who caused it, if any.";

    private final SecurityEvents events;

    /**
     * Creates the controller.
     *
     * @param events reads the audit log
     */
    public SecurityEventController(SecurityEvents events) {
        this.events = events;
    }

    /**
     * Lists the caller's security events.
     *
     * @param jwt      the caller's token
     * @param pageable page and size
     * @return one page of events
     */
    @GetMapping("/me/security-events")
    @Operation(
            operationId = "listCurrentUserSecurityEvents",
            summary = "List the current user's security activity",
            description = DESCRIPTION + " A failed login from an unknown address is a sign the password is targeted.")
    @ApiResponse(responseCode = "200", description = "One page of events")
    @SortableBy(SecurityEvents.SORTABLE_PROPERTIES)
    public Page<SecurityEventResponse> mine(
            @AuthenticationPrincipal Jwt jwt,
            @ParameterObject @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return events.forUser(TokenService.userId(jwt), pageable);
    }

    /**
     * Lists a user's security events. Administrators can read any user's, also of deleted accounts; other users only
     * their own.
     *
     * @param id       user id
     * @param pageable page and size
     * @return one page of events, empty for an id without events
     */
    @GetMapping("/{id}/security-events")
    @PreAuthorize("hasRole('ADMIN') or #id.toString() == authentication.name")
    @Operation(
            operationId = "listUserSecurityEvents",
            summary = "List a user's security activity (admin or self)",
            description = DESCRIPTION + " Events outlive the account, so a deleted user's can still be read.")
    @ApiResponse(responseCode = "200", description = "One page of events")
    @SortableBy(SecurityEvents.SORTABLE_PROPERTIES)
    public Page<SecurityEventResponse> forUser(
            @PathVariable Long id,
            @ParameterObject @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return events.forUser(id, pageable);
    }
}
