/**
 * Outgoing email through a transactional outbox: messages are stored with the change that
 * triggers them and sent with retries, so an SMTP outage or a restart never loses an email.
 * {@link com.hikmethankolay.template.mail.MailRenderer} builds the HTML and text bodies from the templates in
 * {@code src/main/resources/mail/}.
 */
package com.hikmethankolay.template.mail;
