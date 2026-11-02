package com.hikmethankolay.template.token.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hikmethankolay.template.token.AccessTokenScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One of the caller's access tokens. The token itself is shown only once, in {@link CreatedAccessTokenResponse}.
 *
 * @param id         id to use in {@code DELETE /api/v1/users/me/access-tokens/{id}}
 * @param name       the label given at creation
 * @param scope      what the token may do
 * @param createdAt  when it was created
 * @param expiresAt  when it stops working
 * @param lastUsedAt when it was last used, to the minute; left out if never
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"id", "name", "scope", "createdAt", "expiresAt"})
public record AccessTokenResponse(
        long id, String name, AccessTokenScope scope, Instant createdAt, Instant expiresAt, Instant lastUsedAt) {}
