---
title: 'ADR 14: Rate limiting and login lockout'
description: Why requests are limited per client IP with a Redis sliding window, and failed logins by their own lockout.
---

**Status:** accepted

## Context

A public API needs a cap on how fast one client can call it, and a much tighter one on the endpoints attackers hammer:
sign-up, login, refresh and password reset (OWASP API4). The app runs as several instances, so a counter in each
instance's memory would multiply the limit by the replica count. Guessing passwords is a different problem: what
matters is how many wrong passwords an IP or an account has collected, not how many requests per minute arrive.

## Decision

**A sliding window in Redis.** `RateLimitFilter` counts every request in a Redis key per client and window, with one
Lua script that increments the current window and adds the previous window's count weighted by how much of it still
overlaps. A plain fixed window would let a client send twice the limit around the turn of a minute. Keys are
`<spring.application.name>:rate-limit:{<client>}:…`; the braces put all of one client's keys in the same Redis Cluster
slot.

**Per IP, with stricter routes on top.** Every client IP gets `app.rate-limit.requests-per-minute`
(`RATE_LIMIT_PER_MINUTE`, default 120). Each entry in `app.rate-limit.routes` adds its own window for the paths it
matches, e.g. 5 per hour on `/api/v1/auth/register/**` and `/api/v1/auth/password-reset/**`, 30 per minute on
`/api/v1/auth/login/**`. There is no per-user key, and register, login and password reset have no user yet. Actuator
is exempt so probes are never throttled, and `RATE_LIMIT_ENABLED=false` turns the filter off.

**Standard headers and a problem detail.** Every limited response carries the IETF draft headers `RateLimit-Limit`,
`RateLimit-Remaining` and `RateLimit-Reset` of the limit closest to running out. Over the limit the filter answers
`429` with `Retry-After` and the `urn:problem:rate-limited` problem detail (ADR 15), rendered by passing an
`ApiException` to Spring MVC's `HandlerExceptionResolver`.

**A separate lockout for credentials.** `LoginAttemptService` counts attempts per IP (`LOGIN_MAX_ATTEMPTS`, 10) and per
account (`LOGIN_ACCOUNT_MAX_ATTEMPTS`, 100), plus wrong second-factor codes per account (`LOGIN_MFA_MAX_ATTEMPTS`, 10).
An attempt is counted before the password is checked and taken back when it succeeds or ends in a server error, so
concurrent requests cannot pass a stale count. A blocked key answers `429` `urn:problem:login-locked` with
`Retry-After` set to `LOGIN_LOCK_DURATION` (1h). The same check guards every endpoint that asks for the current
password again. The account limit is the higher one, so one client cannot lock a real user out.

**One notion of a client.** Both mechanisms key on `ClientIp.limitKey(request.getRemoteAddr())`: an IPv4 address as
it is, an IPv6 address as its /64. Behind a proxy, `FORWARD_HEADERS_STRATEGY=native` makes Tomcat take the address
from `X-Forwarded-For`, trusting only `server.tomcat.remoteip.internal-proxies`; in `prod`, `ClientAddressCheck`
counts non-public client addresses as `client_address_not_public_total` and logs a warning. When Redis is down the
rate limit allows requests and the login refuses them, as [ADR 2](../0002-redis-fail-open/) records.

## Consequences

- A misconfigured proxy makes every user one client: the first to hit a limit locks out everyone. The warning and
  counter catch it; see [Client IP behind a proxy](../../guides/production/#client-ip-behind-a-proxy).
- Users behind one NAT or carrier IP share a budget. Add a per-user key for authenticated traffic if that bites.
- Each request costs one Redis round trip per window that applies: the global one plus each matching route.
- An attacker with about 10 IPs can keep a known account locked; see
  [Brute-force protection](../../guides/security/#brute-force-protection) and
  [Rate limiting](../../guides/security/#rate-limiting).
