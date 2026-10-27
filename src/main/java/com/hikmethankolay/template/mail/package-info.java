/**
 * Outgoing email through a transactional outbox: messages are stored with the change that
 * triggers them and sent with retries, so an SMTP outage or a restart never loses an email.
 * {@link com.hikmethankolay.template.mail.MailRenderer} builds the HTML and text bodies from the templates in
 * {@code src/main/resources/mail/}. {@link com.hikmethankolay.template.mail.MailCategory} decides whether a
 * recipient can turn an email off; {@link com.hikmethankolay.template.mail.EmailPreferenceService} keeps their
 * choices, and the signed links of {@link com.hikmethankolay.template.mail.UnsubscribeTokens} turn a category off
 * without logging in.
 */
package com.hikmethankolay.template.mail;
