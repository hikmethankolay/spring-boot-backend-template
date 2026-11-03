---
title: 'ADR 18: Scheduled jobs on several instances'
description: Why every @Scheduled job runs on every instance and relies on PostgreSQL row and advisory locks instead of a scheduler lock.
---

**Status:** accepted

## Context

The template runs as several replicas behind an autoscaler, and `@EnableScheduling` starts every `@Scheduled` method on
each of them. Run naively, two instances send the same email or webhook twice, or resume the same stalled broadcast
twice. The usual fix is a scheduler lock (ShedLock or Quartz clustering): one more dependency and table, and a lock row
that a crashed holder leaves behind until it times out. PostgreSQL, already required, offers row locks and advisory
locks that end with the transaction.

## Decision

**Idempotent jobs run everywhere, unlocked.** The hourly cleanups (`AccountService.deleteExpiredTokens`,
`RefreshTokenService.deleteExpiredTokens`, `AccessTokenService.deleteExpiredTokens`, `KnownDevices.forgetUnusedDevices`,
`EmailOutbox.deleteExpiredDeadLetters`, `WebhookDispatcher.deleteOldDeliveries`) and the daily `AuditRetention.purge`
are `DELETE`s by age, staggered across the hour. When two instances run one together, the second finds nothing left
to delete. Most say "Safe to run on several instances at once" in their Javadoc; a new job must be too.

**Queues are claimed with `FOR UPDATE SKIP LOCKED`.** `EmailOutbox.sendDue` (every `MAIL_OUTBOX_POLL_INTERVAL`, 10 s)
locks due rows through `OutboxEmailRepository.lockDue` and sends each in its own transaction, so instances share the
queue without sending an email twice ([ADR 3](../0003-email-outbox/)). `WebhookDispatcher.deliverDue` (every
`WEBHOOKS_POLL_INTERVAL`, 5 s) locks one delivery at a time and leases it before the HTTP call
([ADR 11](../0011-webhooks/)). More instances mean more throughput, not duplicates.

**Singletons take an advisory lock.** `BroadcastService.resumeStalled` runs every minute, but only the instance that
gets `pg_try_advisory_xact_lock(START_LOCK)` claims the broadcasts without progress for five minutes; the others skip
the run. Starting a broadcast waits on the same key with `pg_advisory_xact_lock`. Transaction-scoped locks are released
by the commit, rollback or a dropped connection, so a crashed instance never holds one. The same tool serializes
per-user work outside the scheduler (`NoteRepository.lockUser`, `WebhookEndpointRepository`, `KnownDevices`, account
token emails) on `hashtextextended` keys.

**Background work on `applicationTaskExecutor`.** Queuing a broadcast runs on Boot's executor, injected by name, not
on a thread of its own, so `spring.task.execution.shutdown` gives it 25 s to finish when the pod stops; the scheduler
gets the same (`spring.task.scheduling.shutdown`), so an email being sent is not interrupted and sent again. With
`spring.threads.virtual.enabled=true` both use virtual threads. `WebhookSender` keeps its own deadline scheduler,
since with virtual threads off Boot's scheduler has one thread, held by the dispatcher.

**Failures are visible.** Boot times every `@Scheduled` method as `tasks_scheduled_execution_seconds`;
`ScheduledTaskFailing` fires when one ends with `outcome="ERROR"` within 30 minutes, labelled by `code_function`
(see the [runbook](../../guides/runbooks/#scheduledtaskfailing)).

## Consequences

- Every instance polls the outbox and webhook tables; with many replicas, lengthen the poll intervals.
- Cleanups run N times per hour across N instances; each is a cheap `DELETE`, so this was left alone.
- Advisory-lock keys are global to the database: keep `START_LOCK` and the `hashtextextended` prefixes unique when
  adding locks, or apps sharing one database may block each other.
- A job that must run exactly once and is not idempotent needs a lease row or an advisory lock; revisit a scheduler
  library once there are several such jobs.
