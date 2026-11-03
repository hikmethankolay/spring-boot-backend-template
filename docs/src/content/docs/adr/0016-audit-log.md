---
title: 'ADR 16: Audit log'
description: Why security events go to a dedicated logger, a metric and an append-only table the app cannot rewrite.
---

**Status:** accepted

## Context

After an incident someone asks who logged in, who changed a role, who deleted an account, and from where. Plain
application logs answer badly: they are rotated with everything else, they can record an action that rolled back, and
whoever compromised the app can usually reach them. OWASP Logging and ASVS 5.0 V16 ask for a trail that is structured,
complete and tamper resistant. Users also benefit from seeing their own recent activity.

## Decision

**A closed list of events.** Every traceable action is an `AuditEvent` constant (`auth.login_failed`,
`admin.role_changed`, `webhook.disabled`, …) with a log level: `WARN` for those worth alerting on, `INFO` otherwise.
Feature code calls `AuditLog.record(event, userId, details…)`; the actor (the authenticated principal) and the client
IP are taken from the current request. Passwords and tokens are never passed; failed logins carry an `identifierHash`.

**Three outputs.** Each entry is a structured line on the `audit` logger (separate JSON fields with the `prod`
profile, for long-term log storage), a tick of the `security.audit.events` counter tagged with `event`
(`security_audit_events_total`), and a row in `audit_events` (`V12__audit_events.sql`): columns for the action, user,
actor and IP, the rest as JSON in `details`. The table has no foreign key to `users`, so the trail outlives the
accounts it is about.

**Recorded with the change.** Inside a writable transaction the row is inserted on the same connection behind a JDBC
savepoint (`JpaTransactionManager` does not support `PROPAGATION_NESTED`), and the log line and counter wait for the
commit, so a rolled-back action leaves no trace. Outside one, the row gets a transaction of its own. `occurred_at` is
`clock_timestamp()`, not the transaction start. A failed insert is logged and the request goes on.

**Append-only in the database.** A statement trigger refuses `UPDATE`, `DELETE` and `TRUNCATE` from every role but the
table owner, so the app's role (see [Production](../../guides/production/#database-roles)) cannot cover its tracks.
Rows leave only through `purge_audit_events`, a `SECURITY DEFINER` function with a fixed search path that refuses any
cutoff inside the last 90 days. `AuditRetention` calls it daily at 03:15 with `app.audit.retention` (`AUDIT_RETENTION`,
365 days; the app refuses to start below 90), in batches of 10,000 since `V22`, so a year of events never becomes one
statement that outlasts `statement_timeout`.

**Read per user.** `SecurityEvents` serves `GET /api/v1/users/me/security-events`, the administrators' view and the
data export from the `(user_id, occurred_at DESC, id DESC)` index (`V19`). An actor other than the user is shown as
`admin`, never by id; the table stores the real one.

**A leaf package.** `ArchitectureTest` forbids `audit` (and `common`) from depending on any feature package: every
feature records events, so a dependency back would make a cycle. Endpoints that read the trail live in the feature that
owns them (`user.SecurityEventController`). See [Security](../../guides/security/#audit-log) for the event list.

## Consequences

- Each audited write costs one more insert and a savepoint. Heavy event volume would call for partitioning
  `audit_events` by month and dropping old partitions instead of batched deletes.
- The table keeps `user_id` and `client_ip` after the account is deleted, for `AUDIT_RETENTION`; name that period in
  your privacy notice. Lowering the 90-day floor needs a new migration.
- The protection holds only with separate owner and app roles; a single role that owns the schema can drop the trigger.
- No search across users through the API; that is left to the `audit` log lines and your log platform.
