package com.hikmethankolay.template.token;

import com.hikmethankolay.template.user.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of personal access tokens ({@code app.access-tokens}), so each deployment decides how much a token may do.
 *
 * @param enabled         {@code false} rejects every access token and the endpoints that create them
 * @param maxRole         the highest role a token acts with: a token of a user above it acts with this role instead.
 *                        {@code USER} keeps administrator and owner endpoints for interactive logins only
 * @param allowWriteScope {@code false} makes every token read-only, whatever scope it was created with
 * @param maxLifetime     the longest lifetime a token may be created with
 * @param maxPerUser      how many unexpired tokens a user may have
 */
@Validated
@ConfigurationProperties(prefix = "app.access-tokens")
public record AccessTokenProperties(
        boolean enabled,
        @NotNull Role maxRole,
        boolean allowWriteScope,

        @NotNull @DurationMin(days = 1, message = "must be at least one day")
        Duration maxLifetime,

        @Positive int maxPerUser) {}
