package com.hikmethankolay.template.user;

/**
 * Published inside the transaction that deleted an account, by its owner or an administrator.
 *
 * @param userId the deleted user
 */
public record UserDeleted(long userId) {}
