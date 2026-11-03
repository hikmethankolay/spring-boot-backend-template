---
title: Webhooks
description: Send signed events to your users' endpoints, publish events from your own features, and verify them on the receiving side.
---

Webhooks let other systems react to what happens in your application without polling the API. A user registers an
endpoint URL and the events it wants; the application POSTs each event to it as JSON, signed with the endpoint's
secret per [Standard Webhooks](https://www.standardwebhooks.com), retried until the receiver accepts it
([ADR 11](../../adr/0011-webhooks/)).

## Registering an endpoint

```bash
curl -s -X POST localhost:8080/api/v1/webhooks -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/webhooks","description":"CRM sync","eventTypes":["note.created","note.deleted"]}'
# {"secret":"whsec_…","endpoint":{"id":3,"url":"https://example.com/webhooks","enabled":true,...}}
```

The `whsec_` secret is shown only in this response; `POST /api/v1/webhooks/{id}/secret` replaces it (the old one
keeps signing for 24 hours). Any authenticated caller may manage their own endpoints, including a
[personal access token](../security/#personal-access-tokens) with the `WRITE` scope, so an integration can register
its own receiver. A user has at most `WEBHOOKS_MAX_ENDPOINTS_PER_USER` (10) endpoints.
`POST /api/v1/webhooks/{id}/test` queues a `webhook.test` event (`data`: `{endpointId}`).

| Event | Sent to | `data` |
|---|---|---|
| `note.created`, `note.updated`, `note.deleted` | the note owner's endpoints | `{id}` |
| `user.registered` | endpoints of users with at least `WEBHOOKS_ADMIN_EVENTS_MIN_ROLE` (default `ADMIN`) | `{userId, username}` |
| `user.deleted` | the same | `{userId}` |

Subscribing to `user.*` without that role answers `403`, as does changing, rotating, testing or retrying an endpoint
that has them; the check uses both the stored role and the role of the request, so a personal access token capped
below it cannot use such an endpoint. The role is checked again on every event, so a demoted or suspended user stops
receiving them.

## What a receiver gets

```http
POST /webhooks HTTP/1.1
Content-Type: application/json
webhook-id: msg_6f1c0a5e3b6d4b2f9a7e8c1d2b3a4f5e
webhook-timestamp: 1767225600
webhook-signature: v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=

{"type":"note.created","timestamp":"2026-01-01T00:00:00Z","data":{"id":42}}
```

To verify, compute HMAC-SHA256 over `{webhook-id}.{webhook-timestamp}.{raw body}` with the Base64-decoded secret
(without `whsec_`), and compare it in constant time with any `v1,` value of `webhook-signature` (there are two during
a secret rotation). Reject timestamps more than five minutes away from your clock. The
[Standard Webhooks libraries](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries) do all of
this:

```python
from standardwebhooks.webhooks import Webhook
payload = Webhook(secret).verify(request.body, request.headers)  # raises if the signature is wrong
```

Answer `2xx` quickly and do the work afterwards. Deliveries are **at least once and unordered**: drop duplicates by
`webhook-id`, and fetch the current state through the API (`GET /api/v1/notes/42`) instead of trusting the order of
events. Payloads carry ids on purpose, so personal data never travels to a stale endpoint.

## Retries and the delivery log

Anything but a `2xx` (redirects included) is a failure, retried after 30s, 2m, 10m, 1h, 6h and then daily, up to
`WEBHOOKS_MAX_ATTEMPTS` (8 by default, about 2 days and 7 hours). A `410 Gone` answer, or `WEBHOOKS_DISABLE_AFTER_FAILURES`
failed attempts in a row, turns the endpoint off, gives up its pending deliveries and emails its owner;
`PATCH /api/v1/webhooks/{id}` with `{"enabled": true}` turns it back on.

`GET /api/v1/webhooks/{id}/deliveries` lists recent deliveries with their status, attempts, last HTTP status and
error; `POST /api/v1/webhooks/{id}/deliveries/{deliveryId}/retry` sends one again with the same `webhook-id`. Finished
deliveries are deleted after `WEBHOOKS_DELIVERY_RETENTION`. Metrics:
`webhook_deliveries_total{outcome="sent|retry|failed|skipped"}`.

## Publishing events from your feature

1. Add a constant to `WebhookEventType` with its name and audience (`OWNER`: the user it is about; `ADMINS`:
   application-wide).
2. Call `WebhookEvents.publish` inside the transaction of the change:

   ```java
   @Transactional
   public InvoiceResponse create(long userId, InvoiceRequest request) {
       Invoice invoice = invoices.save(new Invoice(userId, request.amount()));
       webhookEvents.publish(WebhookEventType.INVOICE_CREATED, userId, Map.of("id", invoice.getId()));
       return InvoiceResponse.from(invoice);
   }
   ```

   One delivery row per subscribed endpoint is written with the change, so the event goes out if and only if the
   change commits. `NoteService` is the worked example.

## How it is sent, and the SSRF guard

`WebhookDispatcher` polls every `WEBHOOKS_POLL_INTERVAL` on every instance. It locks a due delivery with
`FOR UPDATE SKIP LOCKED`, leases it and commits, sends it with no transaction open, then records the outcome, so a slow
receiver never holds a database connection and several instances never send the same row at once.

A user-supplied URL could point at internal services (`localhost`, Redis, the metrics port, cloud metadata at
`169.254.169.254`). `WebhookAddressPolicy` refuses any host that resolves to a loopback, private, link-local, unique
local, multicast, carrier-grade NAT or other reserved address, when the endpoint is saved **and** on every connection:
Apache
HttpClient resolves hosts through it, so a name that later resolves to an internal address (DNS rebinding) is refused
too. Signing secrets are encrypted in the database under `WEBHOOK_ENCRYPTION_KEY`, a key of their own. Redirects are never followed and response bodies are never read. In production keep
`WEBHOOKS_ALLOW_PRIVATE_ADDRESSES=false` and `WEBHOOKS_REQUIRE_HTTPS=true` (the `prod` profile's default; the startup
check warns otherwise), which also allows only port 443. For defence in depth, also restrict the pod's egress with a
NetworkPolicy.
