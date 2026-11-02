---
title: 'ADR 10: Personal access tokens'
description: Why scripts and integrations get long-lived, revocable API keys checked against the database, and how they reuse the JWT pipeline.
---

**Status:** accepted

## Context

Every authenticated request needed a JWT from an interactive login: 15 minutes long, renewed with a rotating refresh
token. That fits browsers and apps, not a cron job, a CLI or a third-party integration, which then store the user's
password or a refresh token, both far more powerful than they need. GitHub, GitLab and most SaaS APIs answer this with
personal access tokens: random, scoped, expiring keys a user creates and revokes.

## Decision

**Opaque, hashed tokens.** A token is `pat_` plus 32 random bytes (Base64url). Only its SHA-256 is stored in
`access_tokens`, as for refresh tokens; the prefix tells it apart from a JWT and lets secret scanners match it. It is
shown once, at creation.

**Checked on every request.** `AccessTokenDecoder` wraps the resource server's `JwtDecoder`: a `pat_` value is looked
up with its user, any other value is verified as a JWT. Reading the database each time makes revocation, suspension
and demotion immediate without a Redis denylist. The token becomes an in-memory `Jwt` with the login's `sub`,
`username` and `roles` claims plus `pat_id` and `pat_scope`, so no controller changes and `@PreAuthorize` keeps
working.

**Deployment-controlled power.** A token acts with its user's current role, so administrators can script admin work,
but `app.access-tokens.max-role` caps it (set `USER` to keep admin endpoints interactive), `allow-write-scope=false`
makes every token read-only, and `enabled=false` turns the feature off. Each token also carries its own `READ` or
`WRITE` scope.

**A fixed boundary around the account.** `AccessTokenPolicy` refuses tokens on `/api/v1/users/me/**` except
`GET /api/v1/users/me`. Credentials, two-factor, sessions, linked accounts, the export and the tokens themselves need
an interactive login, so a leaked token cannot take the account over or create more tokens. Creating one needs the
password, sends a security alert and is audited; ending a user's sessions deletes their tokens.

## Consequences

- Two primary-key reads per token request. Cache them in Redis if token traffic dominates; revocation would then lag
  by the cache lifetime.
- Scopes are coarse (`READ`/`WRITE`). Per-resource scopes would need a scope check in each controller; add them when a
  real integration needs them.
- `POST /api/v1/auth/logout` with a token in the header only records a logout; tokens are revoked through their own
  endpoint.
