---
title: 'ADR 2: Redis-backed protections fail open, except login'
description: Why rate limiting and token revocation allow requests when Redis is down, and login does not.
---

**Status:** accepted, amended in 2.9.2 (login fails closed)

## Context

Login lockout, the per-IP rate limit and the access-token denylist live in Redis. Redis can be unavailable while
PostgreSQL and the application are healthy.

## Decision

The rate limit and the revocation check fail open: when Redis does not answer within its short timeout (500 ms), the
request is allowed and a warning is logged. The login lockout fails closed: login answers `503` with the problem type
`urn:problem:login-unavailable` until Redis is back. Redis runs with `noeviction`, so counters and revocations are
never silently dropped while it is up.

Login is the exception because it is the one endpoint where a missing limit is directly exploitable: without the
lockout, an outage is a window for unlimited password guessing. Logins are a small share of traffic, and signed-in
clients keep working, since access tokens are verified without Redis and refresh tokens are in PostgreSQL.

## Consequences

- A Redis outage keeps the API available for signed-in clients instead of turning into a full outage; new logins
  fail until Redis is back.
- During the outage, rate limiting is off, and a revoked access token is accepted until it expires (at most
  `JWT_ACCESS_TOKEN_TTL`). Refresh tokens are in PostgreSQL and stay revocable.
- Redis has no alert of its own: the burst of warnings it causes fires `WarningLogSpike`, and
  [its runbook](../../guides/runbooks/#warninglogspike) covers what to watch for while it lasts.
