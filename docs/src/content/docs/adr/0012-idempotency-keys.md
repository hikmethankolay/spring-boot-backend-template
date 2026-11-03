---
title: 'ADR 12: Idempotency keys for POST'
description: Why a POST retried with the same Idempotency-Key gets the first response from Redis instead of running again.
---

**Status:** accepted

## Context

`PUT`, `PATCH` and `DELETE` can be repeated safely; `POST` cannot. A client whose `POST /api/v1/notes` or broadcast
timed out does not know whether it ran, and retrying it may create a second note or queue a second broadcast. The IETF
`Idempotency-Key` header draft answers this: the client names the attempt, and the server answers every retry of it
with the first response.

## Decision

**One filter for every signed-in POST.** `IdempotencyFilter` is a servlet filter that runs after Spring Security, so
no controller changes. It acts only on a `POST` with the header from a signed-in caller; a key must have 1 to 255
characters, or the request gets `400` `urn:problem:idempotency-key-invalid`.

**Keys belong to the caller.** The Redis key is `<spring.application.name>:idempotency:{<user id>}:` plus the SHA-256
of the client's key, so two users can use the same value; the braces keep a caller's keys in one Redis Cluster slot.
The request is fingerprinted by the SHA-256 of its path, query and body.

**Claim, then keep.** The first request claims the key with `SET NX` for one minute, so a request whose instance stops
does not block its key for a day. Its status, body, `Content-Type` and `Location` are then kept for 24 hours by one Lua
script (Redis runs with `noeviction`, so a response must never be left without a TTL). A retry gets that response with
`Idempotent-Replayed: true`; one while the first still runs gets `409` `urn:problem:idempotency-key-in-use`; the same
key with another path or body gets `422` `urn:problem:idempotency-key-reused`. A `5xx`, `408`, `409` or `429` is not
kept and its claim is released, so the retry runs again.

**Not where a replay would leak.** Anonymous requests are ignored: a replayed login would hand its tokens to anyone
who knows the key. `UNCOVERED_PATHS` (`/api/v1/users/me/mfa/**`, `/api/v1/users/me/access-tokens`, `/api/v1/webhooks`
and `/api/v1/webhooks/*/secret`) are ignored too: their responses hold two-factor secrets, recovery codes, access
tokens or signing secrets, which must not be copied to Redis. A body over 1 MiB is not buffered and runs without the
key; `spring.jackson.factory.constraints.read.max-document-length` caps JSON bodies at the same size.

**Fails open (ADR 2).** If Redis does not answer when the key is claimed, the header is ignored and the request runs.
If it fails while a retry is checked against a taken key, the retry gets `409` and can be sent again.

## Consequences

- Without Redis a retry can run twice, as it would without the header. A deployment that cannot accept that needs a
  database-backed store or a unique constraint on the created row.
- A request that runs longer than the one-minute claim and is retried after it runs a second time; none of the
  template's endpoints come close. Lengthen `IN_PROGRESS_TTL` for a slow endpoint.
- The 24-hour TTL, the 1 MiB limit and the covered paths are constants, not properties. A new `POST` whose response
  holds a secret must be added to `UNCOVERED_PATHS`.
- Kept responses are Redis memory: up to 1 MiB per key for a day. See
  [Retrying POST requests](../../guides/api/#retrying-post-requests).
