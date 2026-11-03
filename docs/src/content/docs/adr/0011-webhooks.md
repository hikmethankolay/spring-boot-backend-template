---
title: 'ADR 11: Outbound webhooks'
description: Why webhooks follow Standard Webhooks, go through an outbox with leased deliveries, and resolve every host through an SSRF guard.
---

**Status:** accepted

## Context

Integrations want to react to events (a note was created, someone signed up) without polling. Sending an HTTP request
from inside a business transaction would make the change depend on a stranger's server and could send events for
changes that roll back. Letting users choose the URL also lets them aim the server at internal services: server-side
request forgery, OWASP's A10.

## Decision

**Standard Webhooks.** Payloads are `{type, timestamp, data}` with `webhook-id`, `webhook-timestamp` and a
`webhook-signature` of `v1,` HMAC-SHA256 over `id.timestamp.body`, so receivers verify with existing libraries in most
languages. Secrets are `whsec_` plus 32 random bytes; a rotation signs with both secrets for 24 hours.

**Their own encryption key.** Secrets are stored with the same AES-256-GCM scheme as TOTP secrets (owner id as
authenticated data, comma-separated key list for rotation) but under `WEBHOOK_ENCRYPTION_KEY` and a separate HKDF info
(`webhook-secret-v1`). Reusing `MFA_ENCRYPTION_KEY` would have saved a setting, but tied the two rotations together
and let a value be copied between the two columns and still decrypt. If a secret no longer decrypts (its key was
dropped), its endpoint is turned off with that reason instead of blocking the delivery queue; rotating the secret
and turning the endpoint on recovers it.

**An outbox with leases.** `WebhookEvents.publish` writes one delivery per subscribed endpoint in the caller's
transaction. `WebhookDispatcher` locks a due row with `FOR UPDATE SKIP LOCKED`, moves its due time past the attempt
and commits, sends with no transaction open, and records the outcome in a second transaction. Unlike the email outbox,
which sends inside its transaction, a webhook receiver is untrusted and may take its full timeout; holding a pooled
connection meanwhile would let slow receivers starve the API. A crash mid-send lets the lease expire and the delivery
is sent again: at least once, deduplicated by `webhook-id`.

**Failure policy.** Retries back off from 30 seconds to a day, over about two days and seven hours (8 attempts by
default). `410 Gone` or 24 consecutive
failures turn an endpoint off and email its owner, so dead receivers stop costing work.

**SSRF guard on every connection.** Apache HttpClient 5 (version managed by Spring Boot) resolves hosts through
`WebhookAddressPolicy`, which refuses non-public addresses; checking only when the URL is saved would miss DNS
rebinding, and the JDK client has no resolver hook. Redirects are not followed and bodies not read.

**Thin events, role-checked audiences.** Payloads carry ids; receivers fetch details through the API (personal access
tokens make that easy). Owner events go to the subject's endpoints; application-wide events to users with at least
`app.webhooks.admin-events-min-role`, checked at subscription and on every event.

## Consequences

- At-least-once, unordered delivery is the contract; receivers must be idempotent.
- One more dependency (httpclient5) and one more polling job per instance.
- Fan-out happens at publish time: an application-wide event writes one row per admin endpoint inside the business
  transaction. Fine for tens of endpoints; move fan-out to the dispatcher if that grows.
- Public-address checks cannot see an egress proxy's view of the network; restrict egress with a NetworkPolicy too.
