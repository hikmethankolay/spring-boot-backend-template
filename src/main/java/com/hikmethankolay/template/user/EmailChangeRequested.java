package com.hikmethankolay.template.user;

/**
 * Published when a user asks to change their email address: the {@code account} package emails a confirmation link to
 * the new address, which stays pending until it is opened. Handled after the transaction commits.
 *
 * @param userId id of the user whose address is changing
 */
public record EmailChangeRequested(long userId) {}
