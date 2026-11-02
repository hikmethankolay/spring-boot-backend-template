package com.hikmethankolay.template.user;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.common.SortableBy;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.user.dto.AdminUpdateUserRequest;
import com.hikmethankolay.template.user.dto.ChangePasswordRequest;
import com.hikmethankolay.template.user.dto.DeleteAccountRequest;
import com.hikmethankolay.template.user.dto.PersonalDataExport;
import com.hikmethankolay.template.user.dto.UpdateProfileRequest;
import com.hikmethankolay.template.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /api/v1/users} resource.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Access</th><th>Success</th></tr>
 *   <tr><td>GET</td><td>/me</td><td>authenticated</td><td>200</td></tr>
 *   <tr><td>PATCH</td><td>/me</td><td>authenticated</td><td>200</td></tr>
 *   <tr><td>PUT</td><td>/me/password</td><td>authenticated</td><td>204</td></tr>
 *   <tr><td>DELETE</td><td>/me</td><td>authenticated + password</td><td>204</td></tr>
 *   <tr><td>GET</td><td>/me/export</td><td>authenticated</td><td>200 (attachment)</td></tr>
 *   <tr><td>GET</td><td>/</td><td>ADMIN</td><td>200 (paginated)</td></tr>
 *   <tr><td>GET</td><td>/{id}</td><td>ADMIN or the user</td><td>200</td></tr>
 *   <tr><td>PATCH</td><td>/{id}</td><td>ADMIN</td><td>200</td></tr>
 *   <tr><td>DELETE</td><td>/{id}</td><td>ADMIN</td><td>204</td></tr>
 *   <tr><td>PUT</td><td>/{id}/suspension</td><td>ADMIN</td><td>200</td></tr>
 *   <tr><td>DELETE</td><td>/{id}/suspension</td><td>ADMIN</td><td>200</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
public class UserController {

    private static final String SUSPEND_OWN_ACCOUNT = "The caller's own account";

    private static final String SUSPEND_NEEDS_OWNER =
            "Not an administrator, or the user is an administrator and the " + "caller is not the owner";

    /** Longest {@code q} of the user list: the longest email address. */
    private static final int MAX_QUERY_LENGTH = 254;

    private final UserService userService;

    private final PersonalDataService personalData;

    /**
     * Creates the controller.
     *
     * @param userService  user business logic
     * @param personalData builds the personal data export
     */
    public UserController(UserService userService, PersonalDataService personalData) {
        this.userService = userService;
        this.personalData = personalData;
    }

    /**
     * Returns the authenticated user.
     *
     * @param jwt the caller's token
     * @return the current user, with its version as {@code ETag}
     */
    @GetMapping("/me")
    @Operation(operationId = "getCurrentUser", summary = "Get the current user")
    @ApiResponse(
            responseCode = "200",
            description = "The current user",
            headers = @Header(name = HttpHeaders.ETAG, description = ApiDocs.ETAG, schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(responseCode = "304", description = ApiDocs.NOT_MODIFIED, content = @Content)
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getVersioned(TokenService.userId(jwt)).toResponse();
    }

    /**
     * Partially updates the authenticated user's username and/or email.
     *
     * @param jwt     the caller's token
     * @param ifMatch the {@code ETag} the change is based on, if the client sent one
     * @param request fields to change
     * @return the updated user, with its new version as {@code ETag}
     */
    @PatchMapping("/me")
    @Operation(
            operationId = "updateCurrentUser",
            summary = "Update the current user's profile",
            description =
                    "Changes the username and/or email; omitted fields stay unchanged. Changing the email needs "
                            + "`currentPassword` and is not immediate: the new address is returned as `pendingEmail` "
                            + "and gets a link (to `/verify-email`, like a verification link); once it is opened, it "
                            + "replaces `email` and the old address is told. Sending the current email again cancels "
                            + "the change. Wrong passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(
            responseCode = "200",
            description = "The updated user",
            headers = @Header(name = HttpHeaders.ETAG, description = ApiDocs.ETAG, schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Invalid input (see `errors`), or the email changes and `currentPassword` is missing or wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "Username or email already taken",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "412",
            description = ApiDocs.PRECONDITION_FAILED,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public ResponseEntity<UserResponse> updateMe(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = ApiDocs.IF_MATCH) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    String ifMatch,
            @Valid @RequestBody UpdateProfileRequest request) {
        return userService
                .updateProfile(TokenService.userId(jwt), request, ifMatch)
                .toResponse();
    }

    /**
     * Replaces the authenticated user's password.
     *
     * @param jwt     the caller's token
     * @param request current and new password
     */
    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "changeCurrentUserPassword",
            summary = "Change the current user's password",
            description = "Ends every session of the user, including the current one: log in again afterwards."
                    + " Wrong passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(responseCode = "204", description = "Password changed; every session has ended")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input (see `errors`), or the current password is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void changeMyPassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(TokenService.userId(jwt), request);
    }

    /**
     * Deletes the authenticated user's account and ends its sessions (right to erasure). The current
     * password is required.
     *
     * @param jwt     the caller's token
     * @param request the current password
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "deleteCurrentUser",
            summary = "Delete the current user's account",
            description = "Needs the current password. Deletes the user's data and ends every session."
                    + " Wrong passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(responseCode = "204", description = "Account and data deleted; every session has ended")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input (see `errors`), or the password is wrong",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The owner account cannot be deleted",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void deleteMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request) {
        userService.deleteOwnAccount(TokenService.userId(jwt), request);
    }

    /**
     * Downloads everything stored about the authenticated user as a JSON file (right of access and
     * data portability).
     *
     * @param jwt the caller's token
     * @return {@code 200 OK} with the export as an attachment
     */
    @GetMapping("/me/export")
    @Operation(operationId = "exportCurrentUserData", summary = "Export the current user's personal data")
    @ApiResponse(
            responseCode = "200",
            description = "Everything stored about the user",
            headers =
                    @Header(
                            name = HttpHeaders.CONTENT_DISPOSITION,
                            description = "attachment; filename=\"personal-data.json\"",
                            schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = PersonalDataExport.class)))
    public ResponseEntity<PersonalDataExport> exportMe(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("personal-data.json")
                                .build()
                                .toString())
                .body(personalData.export(TokenService.userId(jwt)));
    }

    /**
     * Lists users, optionally filtered. Supports the standard {@code page}, {@code size} and {@code sort} query
     * parameters, e.g. {@code ?q=alice&role=ADMIN&suspended=false&page=0&size=20&sort=createdAt,desc}; {@code sort}
     * is limited to {@link UserService#SORTABLE_PROPERTIES}.
     *
     * @param q         part of the username or email, any case
     * @param role      only users with this role
     * @param suspended only suspended or only active users
     * @param pageable  requested page
     * @return one page of users
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(operationId = "listUsers", summary = "List users (admin)")
    @ApiResponse(responseCode = "200", description = "One page of users")
    @SortableBy(UserService.SORTABLE_PROPERTIES)
    public Page<UserResponse> list(
            @Parameter(description = "Only users whose username or email contains this text, ignoring case")
                    @RequestParam(required = false)
                    @Size(max = MAX_QUERY_LENGTH)
                    String q,
            @Parameter(description = "Only users with this role") @RequestParam(required = false) Role role,
            @Parameter(description = "`true`: only suspended users; `false`: only active ones")
                    @RequestParam(required = false)
                    Boolean suspended,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return userService.list(q, role, suspended, pageable);
    }

    /**
     * Returns a user by id. Administrators can read any user, other users only themselves.
     *
     * @param id user id
     * @return the user
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id.toString() == authentication.name")
    @Operation(operationId = "getUser", summary = "Get a user (admin or self)")
    @ApiResponse(
            responseCode = "200",
            description = "The user",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    public UserResponse get(@PathVariable Long id) {
        return userService.getById(id);
    }

    /**
     * Partially updates any field of a user.
     *
     * @param id      user id
     * @param jwt     the caller's token
     * @param request fields to change
     * @return the updated user
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            operationId = "updateUser",
            summary = "Update a user (admin)",
            description = "Administrators update regular users. Changing a role, or changing an administrator or "
                    + "the owner, needs the owner; a role change also needs the owner's `currentPassword`. The owner "
                    + "cannot be demoted and the OWNER role cannot be assigned. A new password or role ends every "
                    + "session of that user, so it applies immediately. A new email address is marked unverified."
                    + " Wrong passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Invalid input (see `errors`), or `currentPassword` is missing " + "or wrong for a role change",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an administrator, or the change needs the owner",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "200",
            description = "The updated user",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Username or email already taken, or the change would assign or " + "remove the OWNER role",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public UserResponse update(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AdminUpdateUserRequest request) {
        return userService.update(id, TokenService.userId(jwt), request);
    }

    /**
     * Suspends a user: they cannot log in and their sessions end, but their data is kept.
     *
     * @param id  user id
     * @param jwt the caller's token
     * @return the suspended user
     */
    @PutMapping("/{id}/suspension")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            operationId = "suspendUser",
            summary = "Suspend a user (admin)",
            description =
                    "Ends every session of the user at once and refuses their logins with `403 account-suspended` "
                            + "until the suspension is lifted; their data is kept. Administrators suspend regular users, the "
                            + "owner also administrators; nobody suspends the owner or their own account. Suspending a "
                            + "suspended user changes nothing.")
    @ApiResponse(
            responseCode = "200",
            description = "The suspended user, with `suspendedAt` set",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = SUSPEND_OWN_ACCOUNT,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = SUSPEND_NEEDS_OWNER,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The owner cannot be suspended",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public UserResponse suspend(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return userService.suspend(id, TokenService.userId(jwt));
    }

    /**
     * Lifts a user's suspension.
     *
     * @param id  user id
     * @param jwt the caller's token
     * @return the reactivated user
     */
    @DeleteMapping("/{id}/suspension")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            operationId = "unsuspendUser",
            summary = "Lift a user's suspension (admin)",
            description = "The user can log in again. The same rules as for suspending apply. Reactivating an active "
                    + "user changes nothing.")
    @ApiResponse(
            responseCode = "200",
            description = "The reactivated user, with `suspendedAt` empty",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = SUSPEND_OWN_ACCOUNT,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = SUSPEND_NEEDS_OWNER,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The owner cannot be suspended",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public UserResponse unsuspend(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return userService.unsuspend(id, TokenService.userId(jwt));
    }

    /**
     * Deletes a user. Administrators cannot delete themselves, only the owner deletes administrators
     * (confirmed with the owner's password), and nobody deletes the owner.
     *
     * @param id           user id
     * @param jwt          the caller's token
     * @param confirmation the caller's current password; required to delete an administrator
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "deleteUser",
            summary = "Delete a user (admin)",
            description =
                    "Administrators delete regular users. Deleting an administrator needs the owner and a body "
                            + "with the owner's current `password`. The owner cannot be deleted. Wrong passwords count as failed logins: repeated failures lock the IP and the account (429).")
    @ApiResponse(responseCode = "204", description = "User and data deleted; every session of the user has ended")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input, an administrator deleting their own account this way, "
                    + "or the password is missing or wrong when deleting an administrator",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an administrator, or deleting an administrator without being " + "the owner",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The owner account cannot be deleted",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable because the failed-login counters cannot be checked",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void delete(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) DeleteAccountRequest confirmation) {
        userService.delete(id, TokenService.userId(jwt), confirmation);
    }
}
