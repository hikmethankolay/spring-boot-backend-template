package com.hikmethankolay.template.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A broadcast that was accepted. Its recipients are queued in the background and the outbox sends them over the
 * following minutes; the history shows the final counts once {@code finishedAt} is set.
 *
 * @param id         id of the broadcast in the history
 * @param recipients users expected to get it: the audience, minus those who turned the category off
 */
@Schema(requiredProperties = {"id", "recipients"})
public record BroadcastResult(long id, int recipients) {}
