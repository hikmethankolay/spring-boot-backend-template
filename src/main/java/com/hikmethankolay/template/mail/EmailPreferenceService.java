package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.mail.dto.EmailPreferenceResponse;
import com.hikmethankolay.template.mail.dto.UnsubscribeResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which optional email categories each user receives ({@code email_preferences}). A category without a row is on;
 * transactional categories are always on and cannot be changed.
 *
 * <p>Every change is audited ({@code EMAIL_PREFERENCES_CHANGED}); turning a category off also counts
 * {@code mail.unsubscribe} by category and source. Unsubscribe tokens are never logged.</p>
 */
@Service
public class EmailPreferenceService {

    private static final String INVALID_TOKEN = "The unsubscribe link is malformed or was not issued by this server.";

    private final JdbcTemplate jdbc;

    private final UnsubscribeTokens tokens;

    private final AuditLog audit;

    private final MeterRegistry meterRegistry;

    /**
     * Creates the service.
     *
     * @param jdbc          database access
     * @param tokens        checks unsubscribe tokens
     * @param audit         records preference changes
     * @param meterRegistry registry for the unsubscribe counter
     */
    public EmailPreferenceService(
            JdbcTemplate jdbc, UnsubscribeTokens tokens, AuditLog audit, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Tells whether a user receives a category.
     *
     * @param userId   the user
     * @param category the category
     * @return {@code true} for transactional categories, and for optional ones the user did not turn off
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(long userId, MailCategory category) {
        if (!category.optional()) {
            return true;
        }
        List<Boolean> enabled = jdbc.queryForList(
                "SELECT enabled FROM email_preferences WHERE user_id = ? AND category = ?",
                Boolean.class,
                userId,
                category.key());
        return enabled.isEmpty() || enabled.getFirst();
    }

    /**
     * Lists the optional categories with the user's choice.
     *
     * @param userId the user
     * @return every optional category, in declaration order
     */
    @Transactional(readOnly = true)
    public List<EmailPreferenceResponse> preferences(long userId) {
        Map<MailCategory, Boolean> choices = new EnumMap<>(MailCategory.class);
        jdbc.query(
                "SELECT category, enabled FROM email_preferences WHERE user_id = ?",
                row -> {
                    boolean enabled = row.getBoolean(2);
                    // A row of a category removed from MailCategory is ignored.
                    MailCategory.fromKey(row.getString(1)).ifPresent(category -> choices.put(category, enabled));
                },
                userId);
        return Arrays.stream(MailCategory.values())
                .filter(MailCategory::optional)
                .map(category -> new EmailPreferenceResponse(
                        category.key(), category.title(), category.description(), choices.getOrDefault(category, true)))
                .toList();
    }

    /**
     * Applies the user's choices from the preferences page.
     *
     * @param userId  the user
     * @param choices category key to whether it should be on
     * @return the preferences after the change
     * @throws ApiException 400 if a key is unknown, names a transactional category or has no value
     */
    @Transactional
    public List<EmailPreferenceResponse> update(long userId, Map<String, Boolean> choices) {
        Map<MailCategory, Boolean> changes = new EnumMap<>(MailCategory.class);
        choices.forEach((key, enabled) -> {
            MailCategory category = MailCategory.fromKey(key)
                    .filter(MailCategory::optional)
                    .orElseThrow(() -> ApiException.badRequest(
                            ProblemType.VALIDATION_FAILED,
                            "'" + key + "' is not an email category that can be turned off."));
            if (enabled == null) {
                throw ApiException.badRequest(ProblemType.VALIDATION_FAILED, "'" + key + "' needs true or false.");
            }
            changes.put(category, enabled);
        });
        changes.forEach((category, enabled) -> set(userId, category, enabled, "user"));
        return preferences(userId);
    }

    /**
     * Tells what an unsubscribe link would turn off, without changing anything (mail scanners open links).
     *
     * @param token the token from the link
     * @return the category and whether it is still on
     * @throws ApiException 400 if the token is invalid
     */
    @Transactional(readOnly = true)
    public UnsubscribeResponse unsubscribeStatus(String token) {
        UnsubscribeTokens.Claim claim = verify(token);
        return new UnsubscribeResponse(
                claim.category().key(), claim.category().title(), isEnabled(claim.userId(), claim.category()));
    }

    /**
     * Turns off the category of an unsubscribe link. Using a link twice, or one of a deleted user, changes nothing.
     *
     * @param token    the token from the link
     * @param oneClick whether a mail client sent it (RFC 8058 one-click) rather than the unsubscribe page
     * @throws ApiException 400 if the token is invalid
     */
    @Transactional
    public void unsubscribe(String token, boolean oneClick) {
        UnsubscribeTokens.Claim claim = verify(token);
        set(claim.userId(), claim.category(), false, oneClick ? "one-click" : "link");
    }

    /**
     * Turns off every optional category for the user of an unsubscribe link.
     *
     * @param token the token from the link
     * @throws ApiException 400 if the token is invalid
     */
    @Transactional
    public void unsubscribeAll(String token) {
        long userId = verify(token).userId();
        Arrays.stream(MailCategory.values())
                .filter(MailCategory::optional)
                .forEach(category -> set(userId, category, false, "link"));
    }

    private UnsubscribeTokens.Claim verify(String token) {
        return tokens.verify(token)
                .orElseThrow(() -> ApiException.badRequest(ProblemType.UNSUBSCRIBE_TOKEN_INVALID, INVALID_TOKEN));
    }

    /**
     * Stores a choice unless it is already in effect. The row is written only while the user exists, so a link of a
     * deleted account succeeds without effect and reveals nothing.
     */
    private void set(long userId, MailCategory category, boolean enabled, String source) {
        if (isEnabled(userId, category) == enabled) {
            return;
        }
        int written = jdbc.update("""
                INSERT INTO email_preferences (user_id, category, enabled, updated_at, source)
                SELECT id, ?, ?, now(), ? FROM users WHERE id = ?
                ON CONFLICT (user_id, category)
                DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at, source = EXCLUDED.source
                """, category.key(), enabled, source, userId);
        if (written == 0) {
            return;
        }
        audit.record(
                AuditEvent.EMAIL_PREFERENCES_CHANGED,
                userId,
                "category",
                category.key(),
                "enabled",
                enabled,
                "source",
                source);
        if (!enabled) {
            meterRegistry
                    .counter("mail.unsubscribe", "category", category.key(), "source", source)
                    .increment();
        }
    }
}
