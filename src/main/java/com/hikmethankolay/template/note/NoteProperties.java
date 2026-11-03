package com.hikmethankolay.template.note;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of notes ({@code app.notes}).
 *
 * @param maxPerUser how many notes a user may have, which also bounds the personal data export
 */
@Validated
@ConfigurationProperties(prefix = "app.notes")
public record NoteProperties(@DefaultValue("1000") @Positive int maxPerUser) {}
