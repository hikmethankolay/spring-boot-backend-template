---
title: 'ADR 8: Unsubscribe links are signed, stateless tokens'
description: Why optional emails carry an HMAC-signed token and a one-click List-Unsubscribe header.
---

**Status:** accepted

## Context

Optional emails (product updates, and later broadcasts) must be easy to stop. Gmail and Yahoo require bulk senders
to offer one-click unsubscribe (RFC 8058: a `List-Unsubscribe` HTTPS URL plus `List-Unsubscribe-Post`), to honour it
within two days, and the link must work without logging in, for as long as the email sits in a mailbox. Mailbox
providers call the URL server to server, with no cookies.

Options considered:

- **A random token stored per email or per user**, like `account_tokens`: revocable, but needs a table that grows
  with every email sent, and a cleanup that would eventually break old links.
- **A signed token**: `v1.<base64url(userId.category)>.<base64url(HMAC-SHA256)>`. Nothing is stored, and a link
  works as long as the key does not change.
- **`mailto:` unsubscribe**: needs a monitored mailbox and mail parsing; providers prefer the HTTPS form.

## Decision

Unsubscribe links carry a signed token. The key is derived with HKDF-SHA256 (info `unsubscribe-v1`) from
`MFA_ENCRYPTION_KEY`, not from the JWT keys, whose old key is dropped after a 15-minute overlap.
`MFA_ENCRYPTION_KEY` is a comma-separated list, like `JWT_SECRET`: the first key signs, every listed key verifies,
so it can be rotated without breaking links as long as the old key stays listed (amended November 2026; before, it
could not change at all). Preferences are per user and category (`email_preferences`), not per address, so they follow an
email change. Only optional categories (`MailCategory.optional`) can be turned off; account and security email
cannot, and carry no unsubscribe link.

The `List-Unsubscribe` header points to `POST /api/v1/mail/unsubscribe` on the API (`MAIL_UNSUBSCRIBE_BASE_URL`);
the footer link opens the frontend's `/unsubscribe` page, which shows what will be turned off (`GET`, which changes
nothing because mail scanners open links) and confirms with `POST`.

## Consequences

- Links never expire and are not single-use; using one twice, or one of a deleted account, succeeds without effect.
- The user id in a token is readable, only its integrity is protected. Anyone holding a link can turn that one
  category off for that user, which is the point of the link.
- Removing a key from `MFA_ENCRYPTION_KEY` invalidates every unsubscribe link it signed, and the two-factor secrets
  it still encrypts. A rotation therefore keeps the old key listed; two-factor secrets move to the new key as their
  users sign in, but links in old emails cannot be re-signed. Tokens carry no key id: verifying tries each listed
  key, a few HMACs.
- The opt-out is checked when an email is queued and again when it is sent, so an email waiting in the outbox is
  dropped once its category is turned off.
