package com.hikmethankolay.template.token;

/** What an access token may do. */
public enum AccessTokenScope {
    /** Only safe requests: {@code GET}, {@code HEAD} and {@code OPTIONS}. */
    READ,
    /** Every method. */
    WRITE
}
