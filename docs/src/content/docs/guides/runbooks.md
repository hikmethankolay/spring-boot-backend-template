---
title: Runbooks
description: Service level objectives and what to do when each alert in ops/prometheus/alerts.yml fires.
---

## Service level objectives

Measured on `/api/v1/**` over 30 days. Tune them to your users; the alerts derive from these numbers.

| Objective | Target | Error budget |
|-----------|--------|--------------|
| Availability: requests that do not fail with `5xx` | 99.5% | 0.5% (about 3.6 hours of full outage) |
| Latency: requests that complete within 300 ms | 95% | 5% |

The budget alerts use multiple windows and burn rates: a **fast burn** (14.4×, 2% of the budget in an hour) pages
someone, a **slow burn** (6×, 5% in six hours) can wait for working hours. Both need a short and a long window to
agree, so a brief spike does not page and a fixed problem stops alerting quickly.

Most investigations start the same way: take the `X-Trace-Id` of a failing request (response header, or the `traceId`
field of the logs) and follow it through the logs. Locally, `scripts/db.sh status` shows the backing services and
`scripts/db.sh logs` their logs; in Kubernetes use `kubectl -n backend logs` and `kubectl -n backend get pods`.

## SLO alerts

### ErrorBudgetFastBurn / ErrorBudgetSlowBurn

More than 7.2% (fast) or 3% (slow) of API requests fail with `5xx`.

1. Check `HighServerErrorRate` and `AppInstanceDown`: is one instance or every instance failing?
2. Look up a failing request's trace id in the logs. Database errors point to `DatabasePoolExhausted` or PostgreSQL
   itself; errors right after a deploy point to the deploy (roll back with `kubectl rollout undo`).
3. **Impact:** users see errors; the remaining monthly budget shrinks. Hold risky deploys until it recovers.

### LatencyBudgetBurn

More than 30% of API requests take longer than 300 ms.

1. Check `DatabasePoolExhausted` and `HeapAlmostFull`. Slow queries are cancelled after `DB_STATEMENT_TIMEOUT`.
2. Compare the Grafana latency panel per endpoint: login and register are slow by design (password hashing) and
   count toward the budget; a sudden change on other endpoints is the signal.
3. **Impact:** a slower API; no data at risk.

## Availability

### AppInstanceDown

Prometheus cannot scrape an instance for 2 minutes.

1. `kubectl -n backend get pods`: a crash loop shows its reason in `kubectl -n backend logs --previous <pod>`.
2. A startup failure prints the misconfigured setting (for example a missing `JWT_SECRET`, or, with the `prod`
   profile, a `CORS_ALLOWED_ORIGINS` that still points to localhost; the logs say `The prod profile is running with
   development settings`).
3. **Impact:** none while other replicas are healthy (the PodDisruptionBudget keeps one up).

### HighServerErrorRate / HighLatency

Fast, non-SLO versions of the budget alerts over five and ten minutes; follow the SLO runbooks above.

## Security

### LoginFailureSpike

More than 30 failed logins per minute: likely credential stuffing.

1. Search the `audit` logger for `auth.login_failed` and group by `clientIp`.
2. The per-account lockout and per-IP rate limit already slow the attack. Block persistent sources at the ingress or
   firewall. Lower `RATE_LIMIT_PER_MINUTE` if needed.
3. **Impact:** an account is locked for its real user (`LOGIN_LOCK_DURATION`) only after `LOGIN_ACCOUNT_MAX_ATTEMPTS`
   failures, which takes several IPs, since each IP is blocked after `LOGIN_MAX_ATTEMPTS`.

### RefreshTokenReused

A refresh token was used twice, so it was probably copied. The session was already revoked.

1. Find the `auth.refresh_token_reused` audit entry: user id and client IP.
2. If the user does not recognise the activity, ask them to change their password (this ends every session).
3. **Impact:** that user is logged out on every device of that session.

### PrivilegeChanges

An administrator changed a role. Confirm with the actor in the `admin.role_changed` audit entry that it was intended.

## Logs

### ErrorLogs

An instance logged at level `ERROR`. Search the logs for `"level":"ERROR"` and follow the trace id. Unhandled
exceptions return `500` with a problem detail; the log has the stack trace.

### WarningLogSpike

More than 10 warnings per minute, usually because **Redis is unreachable**: the logs say `Could not check login
failures`, `Rate limit check failed` or `Could not check token revocation`.

1. Check Redis: `scripts/db.sh redis-cli`, then `PING`, locally, or the managed service's status page.
2. **Impact while Redis is down** ([ADR 2](../../adr/0002-redis-fail-open/)): new logins fail with `503`, so no
   password can be guessed meanwhile. Signed-in clients keep working and refresh their tokens (refresh tokens are in
   PostgreSQL), but rate limiting is off, revoked access tokens stay valid until they expire (15 minutes), and the
   cache is bypassed.
3. Once Redis is back, the warnings stop on their own; nothing needs to be replayed.

## Email

### EmailsDropped

Emails were abandoned after every retry. Users did not get verification or password-reset links.

1. Check the SMTP settings (`MAIL_HOST`, `MAIL_PORT`, `SPRING_MAIL_USERNAME`) and the provider's status. A log line
   with `STARTTLS is required but host does not support STARTTLS` means the server offers no TLS on that port: use
   the provider's TLS port (see [Mail encryption](../production/#mail-encryption)).
2. Users can request the email again (`/api/v1/auth/verify-email/resend`, `/api/v1/auth/password-reset`).

If an email is never queued at all, look for a `TemplateInputException` in the log: it names the template that is
missing or broken under `src/main/resources/mail/`. Fix the template, deploy, and let users request the email again.

### EmailBacklog

More than 100 emails waiting for 15 minutes: SMTP is down or slow. Same checks as `EmailsDropped`; the outbox sends
the backlog by itself once SMTP recovers. A broadcast does not trigger it: its emails are counted in
`mail_outbox_broadcast_pending` instead, and wait behind every other email.

## Background jobs

### ScheduledTaskFailing

A `@Scheduled` method (email dispatcher, expired account-token and refresh-token cleanup, audit retention purge)
threw an exception. The `code_function` label names it. Search the logs for the exception; a failing cleanup only
lets old rows pile up, a failing dispatcher also raises `EmailBacklog`.

## Deploys

### Rolling back a release

`kubectl -n backend rollout undo deployment/app` brings back the previous image, but not the previous schema: Flyway
migrations only run forward, and the older version starts against the newer schema (Flyway skips migrations newer than
its own). A rollback is therefore safe only if every migration in the release was **backward compatible**:

| Safe in one release | Needs two releases (expand, then contract) |
|---------------------|--------------------------------------------|
| A new table | Renaming a column or table |
| A new nullable column, or one with a default | Dropping a column or table the previous version still reads |
| A new index (`CREATE INDEX CONCURRENTLY` for large tables) | Making a column `NOT NULL`, or narrowing its type |

For the right-hand column, release the code that no longer needs the old shape first, and drop or rename in the next
release, once rolling back past the first one is no longer an option.

To undo a schema change, write a new migration that reverses it; never edit or delete an applied one. If a migration
failed halfway, PostgreSQL rolled back its transaction (every statement is transactional except `... CONCURRENTLY`),
so fix the file and deploy again. A migration that needs more than `DB_STATEMENT_TIMEOUT` is cut off; see
[Configuration](../configuration/) for running it with a longer timeout.

Before a risky migration, take a backup or note the point-in-time-recovery timestamp: restoring the database is the
only way to get back data that a migration deleted.

## Resources

### DatabasePoolExhausted

Requests wait for a database connection.

1. Look for slow queries: `scripts/db.sh psql`, then `SELECT pid, now() - query_start, query FROM pg_stat_activity
   WHERE state <> 'idle' ORDER BY 2 DESC;`
2. If load simply grew, raise `DB_POOL_SIZE` or add replicas, keeping the total under PostgreSQL's `max_connections`.

### HeapAlmostFull

Heap above 90% for 10 minutes. Raise the container memory limit (the JVM uses 75% of it). If usage keeps climbing
after restarts, take a heap dump to look for a leak.
