package com.hikmethankolay.template.user;

/**
 * Published inside the transaction that deleted an account, by its owner or an administrator.
 *
 * @param userId       the deleted user
 * @param email        the user's address
 * @param pendingEmail the address of an unconfirmed email change, or {@code null}
 */
public record UserDeleted(long userId, String email, String pendingEmail) {}
