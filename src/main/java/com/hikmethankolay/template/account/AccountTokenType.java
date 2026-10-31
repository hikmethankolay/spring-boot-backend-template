package com.hikmethankolay.template.account;

/** Purpose of an {@link AccountToken}; a token is only accepted for its own purpose. */
public enum AccountTokenType {
    /** Confirms ownership of an email address. */
    EMAIL_VERIFICATION,
    /** Allows setting a new password without the old one. */
    PASSWORD_RESET,

    /** Confirms a new email address before it replaces the current one; opened through the verification link. */
    EMAIL_CHANGE
}
