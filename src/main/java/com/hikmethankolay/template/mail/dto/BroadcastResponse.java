package com.hikmethankolay.template.mail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A broadcast in the history.
 *
 * @param id           broadcast id
 * @param category     category it was sent in
 * @param subject      subject line
 * @param title        heading of the email
 * @param roles        roles of the audience
 * @param verifiedOnly whether unverified addresses were skipped
 * @param queued       emails queued
 * @param suppressed   users skipped because they turned the category off
 * @param partial      whether queuing stopped on an error
 * @param createdBy    id of the owner who sent it; left out if that account was deleted
 * @param createdAt    when it was sent
 * @param finishedAt   when its recipients were queued; left out while queuing, or if its instance stopped meanwhile
 *                     ({@code queued} and {@code suppressed} are final once it is set)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        requiredProperties = {
            "id",
            "category",
            "subject",
            "title",
            "roles",
            "verifiedOnly",
            "queued",
            "suppressed",
            "partial",
            "createdAt"
        })
public record BroadcastResponse(
        long id,
        String category,
        String subject,
        String title,
        List<String> roles,
        boolean verifiedOnly,
        int queued,
        int suppressed,
        boolean partial,
        Long createdBy,
        Instant createdAt,
        Instant finishedAt) {}
