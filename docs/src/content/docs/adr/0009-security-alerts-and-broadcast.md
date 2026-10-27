---
title: 'ADR 9: Security alerts, device recognition and owner broadcasts'
description: Why alerts are queued with the change they report, how a new device is recognized, and why the only bulk email is an owner broadcast.
---

**Status:** accepted

## Context

A stolen password or session is often noticed first by its owner, if they are told: a sign-in from a device they
do not have, a password or address they did not change. The application also needs a way to announce something to
its users, without becoming a tool for sending arbitrary email.

To recognize a known device, the common practice is a device cookie: OWASP's "device cookies" issue a signed,
per-browser cookie at login and treat a client without one as unknown, and Okta and GitHub ask to verify, or
report, a browser without their cookie. Client-side fingerprinting (canvas, fonts and similar) needs JavaScript
before login, consent under GDPR and KVKK, and a frontend this template does not ship.

## Decision

**Alerts.** `SecurityMailer` emails the user about a sign-in from a new device, a password change or reset, an
email change (to the old address, never naming the new one), two-factor authentication turned on or off, and a
login provider linked to an existing account. Each alert is queued through the outbox in the transaction of the
change, so a change that rolls back sends nothing. Alerts are transactional (`MailCategory.SECURITY`): they cannot be
turned off and carry no unsubscribe link, and they never contain tokens, codes or new values.

**Devices.** Every login sets a device cookie: a random token, HttpOnly, sent only to `/api/v1/auth`, stored as its
SHA-256 in `known_devices`. A client without the cookie, such as a mobile app, is recognized when the same browser
family and operating system logged in from the same public network (IPv4 /24, IPv6 /64) within the lookback; a
private address may be a proxy's, so only the cookie counts there. The first device of an account is not reported,
and each user's latest device is kept beyond the lookback, so a long-idle account still has its next new device
reported.

**Broadcasts.** The owner can email an announcement to users by role, in an optional category only, with a fixed
template and a plain-text body, after a preview and with their password. The request records the broadcast and
returns; Spring Boot's task executor queues the recipients in batches through the outbox, which skips users who
turned the category off and adds their unsubscribe link. The outbox sends broadcast emails after every other email
waiting. One broadcast at a time, at most one per minute.

There is no endpoint that sends an email to an address chosen by the caller.

## Consequences

- A user learns of a sign-in or a security change they did not make; the alerts can be turned off for the whole
  application (`MAIL_SECURITY_ALERTS_ENABLED`, `MAIL_NEW_LOGIN_ALERT_ENABLED`), not by users.
- Clearing cookies, a new browser, or a cookieless client on a new network is reported as a new device, as with
  any cookie-based scheme.
- Broadcasts go out at the outbox's pace (50 emails per run per instance); a provider's bulk API would be faster
  but ties the template to one provider.
- A broadcast interrupted by a stop longer than the shutdown wait stays unfinished; its queued emails still go out,
  and it stops blocking new broadcasts after an hour.
