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

1. Search the `audit` logger for `auth.login_failed` and group by `clientIp`. For one account,
   `GET /api/v1/users/{id}/security-events` lists its events with time and IP.
2. The per-account lockout and per-IP rate limit already slow the attack. Block persistent sources at the ingress or
   firewall. Lower `RATE_LIMIT_PER_MINUTE` if needed.
3. **Impact:** an account is locked for its real user (`LOGIN_LOCK_DURATION`) only after `LOGIN_ACCOUNT_MAX_ATTEMPTS`
   failures, which takes several IPs, since each IP is blocked after `LOGIN_MAX_ATTEMPTS`.

### RefreshTokenReused

A refresh token was used twice, so it was probably copied. The session was already revoked.

1. Find the `auth.refresh_token_reused` audit entry: user id and client IP.
2. If the user does not recognise the activity, ask them to change their password (this ends every session).
3. **Impact:** that user is logged out on every device of that session.

### Compromised or abusive account

Not an alert: a user reports activity they do not recognise, or an account is abused.

1. Read its events: `GET /api/v1/users/{id}/security-events` (logins, failed logins, password, email and two-factor
   changes, with time and IP).
2. Suspend it: `PUT /api/v1/users/{id}/suspension` ends every session at once and refuses its logins with
   `403 account-suspended`, while keeping its data. Lift it with `DELETE` on the same path once the owner has
   regained control (for example after a password reset). Suspending an administrator needs the owner.
3. **Impact:** only that account; it is logged out everywhere until reactivated.

### PrivilegeChanges

An administrator changed a role. Confirm with the actor in the `admin.role_changed` audit entry that it was intended.

### SecurityControlFailingOpen

Redis did not answer, and a protection let a request through unchecked (`security_fail_open_total{control}`):

| `control` | What was skipped |
|-----------|------------------|
| `rate-limit` | The per-IP request limit |
| `token-revocation` | Checking or recording a revoked access token: a logged-out or revoked token stays valid until it expires |
| `sign-up-link-reuse` | Checking or recording that a sign-up link was already used |
| `sign-up-email-cooldown` | The one-per-minute limit on sign-up emails to an address |

Follow [WarningLogSpike](#warninglogspike): the cause is almost always Redis being unreachable. If revocations failed
(`token-revocation`) while an incident was going on, revoke the affected users' sessions again once Redis is back.

### Client address is not public

Not an alert: `client_address_not_public_total` grows, and the log said "A request came from the non-public address"
once after startup. Requests reach the app from a private, loopback or link-local address, which usually means the
proxy in front of it does not pass the client's address on, so the rate limit, the login lockout and the audit log
see every user as the proxy. Fix the ingress as described in
[Client IP behind a proxy](../production/#client-ip-behind-a-proxy). Clients that really are on the private network
(another service calling the API) also count here; ignore the counter then.

## Leaked secrets

Not alerts: a secret turned up in a log, a repository, a ticket or a former employee's laptop. In Kubernetes the pods
read `app-secrets` and the migration Job reads `app-migrator-secrets`, both only at startup: after changing a key,
run `kubectl -n backend rollout restart deployment/app` (and use the new value for the next migration Job). With
Docker or compose, change the environment and restart the containers. Write down when the secret leaked: that window
is what you search the `audit` log for.

### Database password

1. Set a new password as a superuser: `ALTER ROLE app PASSWORD '…';` (or `app_owner`, if the migration role leaked).
2. Put it in `DB_PASSWORD` of `app-secrets` (or `app-migrator-secrets`) and restart. Running pods keep their open
   connections but cannot open new ones, so restart right away.
3. If the database was reachable with the old password, assume it was read: emails, password hashes and session
   metadata (two-factor secrets are encrypted, refresh tokens are stored as hashes). The `app` role can also write
   rows, so check the users with role `ADMIN` or `OWNER` and recent `admin.*` events. Treat it as a personal data
   breach and check your notification duties (72 hours under GDPR).

### JWT signing key

Follow [Key rotation](../security/#key-rotation) without the overlap: deploy the new `JWT_SECRET` (or
`JWT_PRIVATE_KEY`) alone. Every access token, including any forged one, stops working at once; refresh tokens are not
signed, so clients refresh and carry on. Whoever held the key could sign a token for any user, the owner too: review
the `audit` log of the leak window, above all `admin.*` events.

### SMTP credentials

1. Create new credentials at the mail provider and revoke the old ones.
2. Put them in `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` and restart. Emails queued meanwhile wait in the
   outbox and are retried (see `EmailBacklog`).
3. Check the provider's sending log for mail you did not send: phishing from your domain reaches your users and
   hurts your sender reputation. Warn the users who received it.

### OAuth client secrets

1. Generate a new client secret in the GitHub OAuth app or the Google Cloud console. If the provider keeps both
   secrets valid for a while, deploy the new one before deleting the old; otherwise login with that provider fails
   between the two steps.
2. Put it in `GITHUB_CLIENT_SECRET` or `GOOGLE_CLIENT_SECRET` and restart.
3. **Impact:** the secret lets someone redeem authorization codes as your application, but codes need the PKCE
   verifier and go only to the registered `OAUTH2_REDIRECT_URI`. Linked accounts stay linked.

### MFA_ENCRYPTION_KEY

The key encrypts the two-factor secrets in the database and signs unsubscribe links. On its own it lets someone
forge unsubscribe links, which can only turn off optional emails. Together with a copy of the database it reveals
every authenticator secret, so the second factor of enrolled users is gone (their passwords still protect them).

Key rotation for this setting is being added in a separate change. With it, rotate as for the JWT key: add the new
key first in the list, keep the old one until every stored secret has been re-encrypted, then remove the old key.
Without it, replacing the key breaks every enrolled authenticator and every unsubscribe link already sent; enrolled
users log in with a recovery code and enroll again ([details](../security/#two-factor-authentication)). Either way,
if the database leaked too, re-encrypting is not enough: the secrets themselves are known, so ask enrolled users to
turn two-factor authentication off and on again for a new secret.

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
3. Once Redis is back, the warnings stop on their own. Revocations that failed meanwhile (`Could not revoke the tokens
   of`) are not replayed: those access tokens stay valid until they expire, but their refresh tokens are gone.

## Email

### EmailsDropped

Emails were abandoned after every retry. Users did not get verification or password-reset links.

1. Check the SMTP settings (`MAIL_HOST`, `MAIL_PORT`, `SPRING_MAIL_USERNAME`) and the provider's status. A log line
   with `STARTTLS is required but host does not support STARTTLS` means the server offers no TLS on that port: use
   the provider's TLS port (see [Mail encryption](../production/#mail-encryption)).
2. Users can request the email again (`/api/v1/auth/verify-email/resend`, also for the link of a pending email
   change, and `/api/v1/auth/password-reset`).

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
only way to get back data that a migration deleted (see [Restoring production](#restoring-production)).

## Backups

### Restore drill

A backup counts only once a restore from it has worked. Rehearse a point-in-time restore every quarter and after
any change to the backup setup:

1. Pick a target time a few minutes ago and note it.
2. Restore to a **new** instance, never over production: the managed service's point-in-time restore, or a base
   backup plus WAL replay with `recovery_target_time` (pgBackRest, WAL-G). A physical restore brings back the
   `app_owner` and `app` roles with their grants.
3. Check the copy with `psql`:

   ```sql
   SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1; -- same as production
   SELECT max(occurred_at) FROM audit_events;  -- shortly before the target time
   SELECT count(*) FROM users;                 -- close to production
   ```

4. Check that `MFA_ENCRYPTION_KEY` can be fetched from the secret manager: it is kept out of the backups, and
   without it the restored two-factor secrets cannot be decrypted.
5. Record how long the restore took and how much data it lost (target time against the last WAL archived), then
   delete the instance: it holds personal data.

### Restoring production

1. Scale the app to zero (`kubectl -n backend scale deployment/app --replicas=0`) and restore as in the drill.
2. Before the app starts again, deal with what the restore brought back:
   - **Sessions.** Refresh tokens deleted after the target time (logouts, ended sessions, password changes) exist
     again. Log everyone out: `DELETE FROM refresh_tokens;`.
   - **Emails.** Sent emails are deleted from `email_outbox`, so those sent after the target time are queued again
     and would go out twice. Delete the rows you know were sent, or accept the duplicates.
   - **Changes after the target time** are lost: new users, password, email and two-factor changes. The `audit`
     log lines in your log storage outlive the restore and show who is affected; tell them.
3. Point `DB_URL` at the restored instance if it changed, scale the app back up, and delete the
   `<application name>:users::*` keys in Redis (or wait `CACHE_TTL`) so no profile from before the restore is served.

## Resources

### DatabasePoolExhausted

Requests wait for a database connection.

1. Look for slow queries: `scripts/db.sh psql`, then `SELECT pid, now() - query_start, query FROM pg_stat_activity
   WHERE state <> 'idle' ORDER BY 2 DESC;`
2. If load simply grew, raise `DB_POOL_SIZE` or add replicas, keeping the total under PostgreSQL's `max_connections`.

### HeapAlmostFull

Heap above 90% for 10 minutes. Raise the container memory limit (the JVM uses 75% of it). If usage keeps climbing
after restarts, take a heap dump to look for a leak.

### ContainerOOMKilled

The kernel killed the `app` container for using more memory than its limit (needs kube-state-metrics). A full heap
does not cause this: the JVM exits on `OutOfMemoryError` by itself. The memory outside the heap (metaspace, thread
stacks, direct buffers) did not fit next to it.

1. `kubectl -n backend describe pod <pod>` shows `Last State: Terminated, Reason: OOMKilled`.
2. Raise the memory limit in `deploy/kubernetes/deployment.yaml`, or lower the heap's share of it
   (`-XX:MaxRAMPercentage=75` in the `Dockerfile`'s `ENTRYPOINT`).
3. **Impact:** requests in flight on that pod failed; the other replicas kept serving.
