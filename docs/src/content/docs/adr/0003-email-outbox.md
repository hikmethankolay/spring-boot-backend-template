---
title: 'ADR 3: Emails go through a transactional outbox'
description: Why the application never sends email from the request.
---

**Status:** accepted

## Context

Verification and password-reset emails are triggered by database changes. Sending them from the request couples the
response time to the SMTP server, and an email can be sent for a change that later rolls back, or lost when the
process stops between commit and send.

## Decision

Emails are written to the `email_outbox` table in the same transaction as the change. A scheduled dispatcher sends
them with retries and backoff, and gives up after a fixed number of attempts. An email given up is kept in the table
as a dead letter (`failed_at` set), which the dispatcher skips, so it can be re-queued after a long SMTP outage; dead
letters are deleted after a retention period (`app.mail.dead-letter-retention`, 7 days by default). Any exception from
the send counts as a failed attempt, so a bug in one email cannot make the dispatcher retry it forever.

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/email-outbox-light.svg" width="720" height="588" alt="The service enqueues the email, which is inserted into email_outbox in its transaction; after the commit the outbox locks the row, sends it over SMTP and deletes it or schedules a retry, and a scheduled loop retries due rows with backoff until they are sent or dropped." />
  <img class="light:sl-hidden" src="../../diagrams/email-outbox-dark.svg" width="720" height="588" alt="The service enqueues the email, which is inserted into email_outbox in its transaction; after the commit the outbox locks the row, sends it over SMTP and deletes it or schedules a retry, and a scheduled loop retries due rows with backoff until they are sent or dropped." />
</figure>

## Consequences

- An email is sent only if its change committed, and survives restarts and SMTP outages. Even one given up survives
  until its retention ends; it still holds its links, tokens included, until then.
- Delivery is asynchronous (seconds, not milliseconds), and needs monitoring: `mail_outbox_pending` and
  `mail_outbox_dropped_total` have alerts.
- The commit is normally followed by one immediate send attempt. Emails an anonymous request sends only when an
  account exists (password reset, resent verification links) skip it and wait for the dispatcher
  (`EmailOutbox.enqueueDeferred`): the SMTP round trip would otherwise make those answers slower for registered
  addresses and reveal them.
- Emails are rendered from templates before they are queued; see [ADR 7](../0007-email-templates/).
