package com.hikmethankolay.template.user;

/**
 * Published inside the transaction that created an account, so listeners (such as webhooks) commit with it.
 *
 * @param userId   the new user
 * @param username their username
 */
public record UserRegistered(long userId, String username) {}
