package com.hikmethankolay.template.audit;

import org.slf4j.event.Level;

/**
 * Security-relevant events recorded by {@link AuditLog}. Add an entry here for every new action
 * that should be traceable after an incident.
 */
public enum AuditEvent {

    /** A new account was registered. */
    USER_REGISTERED("user.registered", Level.INFO),

    /** Someone signed up with the email address of an existing account; its owner was told by email. */
    REGISTRATION_REPEATED("user.registration_repeated", Level.INFO),

    /** A user confirmed their email address. */
    EMAIL_VERIFIED("user.email_verified", Level.INFO),

    /** Correct credentials; a session was started. */
    LOGIN_SUCCEEDED("auth.login_succeeded", Level.INFO),

    /** Wrong username, email or password. */
    LOGIN_FAILED("auth.login_failed", Level.WARN),

    /** A login was rejected because the IP or account is locked after too many failures. */
    LOGIN_BLOCKED("auth.login_blocked", Level.WARN),

    /** Correct credentials, but the email address is not verified yet. */
    LOGIN_UNVERIFIED("auth.login_unverified", Level.WARN),

    /** A session was ended by the user. */
    LOGOUT("auth.logout", Level.INFO),

    /** An already used refresh token was presented again: likely stolen. Its session was revoked. */
    REFRESH_TOKEN_REUSED("auth.refresh_token_reused", Level.WARN),

    /** All sessions of a user were revoked, e.g. after a password change. */
    SESSIONS_REVOKED("auth.sessions_revoked", Level.INFO),

    /** A refresh was refused because the session reached its absolute lifetime ({@code app.jwt.max-session-ttl}). */
    SESSION_EXPIRED_ABSOLUTE("auth.session_expired_absolute", Level.INFO),

    /** A user ended one of their sessions ({@code DELETE /api/v1/users/me/sessions/{id}}). */
    SESSION_REVOKED("auth.session_revoked", Level.INFO),

    /** A user changed their own password. */
    PASSWORD_CHANGED("user.password_changed", Level.INFO),

    /** A password reset email was requested for an existing account. */
    PASSWORD_RESET_REQUESTED("user.password_reset_requested", Level.INFO),

    /** A password was set with a reset link. */
    PASSWORD_RESET_COMPLETED("user.password_reset_completed", Level.INFO),

    /** An administrator changed a user's username, email or password. */
    USER_UPDATED_BY_ADMIN("admin.user_updated", Level.INFO),

    /** An administrator changed a user's role. */
    ROLE_CHANGED("admin.role_changed", Level.WARN),

    /** An administrator deleted a user. */
    USER_DELETED_BY_ADMIN("admin.user_deleted", Level.WARN),

    /** A user deleted their own account. */
    ACCOUNT_DELETED("user.account_deleted", Level.INFO),

    /** A user downloaded an export of their personal data. */
    DATA_EXPORTED("user.data_exported", Level.INFO),

    /** Two-factor authentication was turned on. */
    MFA_ENABLED("user.mfa_enabled", Level.INFO),

    /** Two-factor authentication was turned off. */
    MFA_DISABLED("user.mfa_disabled", Level.WARN),

    /** A wrong second-factor code was entered at login. */
    MFA_FAILED("auth.mfa_failed", Level.WARN),

    /** A recovery code was used instead of the authenticator. */
    RECOVERY_CODE_USED("auth.recovery_code_used", Level.WARN),

    /** An external provider account was linked to a user. */
    SOCIAL_ACCOUNT_LINKED("auth.social_account_linked", Level.INFO);

    private final String action;

    private final Level level;

    /**
     * Creates an event type.
     *
     * @param action name used in logs and metrics
     * @param level  log level
     */
    AuditEvent(String action, Level level) {
        this.action = action;
        this.level = level;
    }

    /**
     * Returns the event name used in logs and metrics.
     *
     * @return the action, e.g. {@code auth.login_failed}
     */
    public String action() {
        return action;
    }

    /**
     * Returns the log level of the event.
     *
     * @return {@code WARN} for events worth alerting on, {@code INFO} otherwise
     */
    public Level level() {
        return level;
    }
}
