package com.hikmethankolay.template.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One entry of a user's security activity. The event's other details are left out: a failed login, for example,
 * stores a hash of what was typed as the username, which can be a mistyped password.
 *
 * @param occurredAt when it happened (ISO-8601, UTC)
 * @param event      what happened, e.g. {@code auth.login_succeeded}; see the audit log in the Security guide
 * @param actorId    who caused it: the user's own id, or {@code admin} for anyone else (another user's id is not
 *                   shown); absent if no one was signed in
 * @param clientIp   address of the request; absent for events outside a request
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"occurredAt", "event"})
public record SecurityEventResponse(Instant occurredAt, String event, String actorId, String clientIp) {}
