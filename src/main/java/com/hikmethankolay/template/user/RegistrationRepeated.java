package com.hikmethankolay.template.user;

/**
 * Published when someone signs up with an email address that already has an account. The response does not
 * tell them; the account's owner gets an email instead. Handled after the transaction commits.
 *
 * @param userId id of the account the address belongs to
 */
public record RegistrationRepeated(long userId) {}
