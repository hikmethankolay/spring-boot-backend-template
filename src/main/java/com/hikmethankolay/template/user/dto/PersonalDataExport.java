package com.hikmethankolay.template.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * Everything the application stores about a user, returned by {@code GET /api/v1/users/me/export}
 * (right of access and data portability, GDPR articles 15 and 20).
 *
 * @param exportedAt when the export was created
 * @param profile    the user's account data
 * @param related    data kept by other features, one entry per
 *                   {@link com.hikmethankolay.template.user.PersonalDataProvider}, e.g. {@code sessions}
 */
@Schema(requiredProperties = {"exportedAt", "profile", "related"})
public record PersonalDataExport(
        Instant exportedAt,
        UserResponse profile,

        @Schema(
                description = "Data kept by other features, one entry per section; currently `sessions` (the "
                        + "user's active sessions), `knownDevices`, `accessTokens`, `socialAccounts`, `emailPreferences`, "
                        + "`notes`, `webhooks` and `securityEvents` "
                        + "(the user's audit trail)")
        Map<String, Object> related) {}
