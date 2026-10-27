package com.hikmethankolay.template.mail.dto;

import com.hikmethankolay.template.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * An announcement to email to many users.
 *
 * @param category        optional category it belongs to, e.g. {@code product-updates}; users who turned it off are
 *                        skipped, and every email carries its unsubscribe link
 * @param subject         subject line
 * @param title           heading of the email
 * @param body            plain text; blank lines separate paragraphs. Markup is shown as typed, never rendered
 * @param roles           roles of the users to send to
 * @param verifiedOnly    whether to skip users whose address is not verified
 * @param currentPassword the owner's password; required to send, not to preview
 */
public record BroadcastRequest(
        @NotBlank @Size(max = 40) String category,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 16_000) String body,
        @NotEmpty Set<Role> roles,
        boolean verifiedOnly,
        @Size(max = 128) String currentPassword) {}
