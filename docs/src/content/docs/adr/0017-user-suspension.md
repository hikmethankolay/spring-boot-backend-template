---
title: 'ADR 17: User suspension'
description: Why administrators can block an account without deleting it, and how far the block reaches.
---

**Status:** accepted

## Context

When an account is compromised or abused, administrators had two tools: change its password, which the attacker may
reset again, or delete it, which destroys the data needed to investigate and cannot be undone. They need a switch that
locks the account out everywhere at once, keeps everything, and can be turned back.

## Decision

**A nullable timestamp.** `V16__user_suspension.sql` adds `users.suspended_at`, `NULL` while the account is active.
Being nullable, it lets the previous release run against the new schema during a rolling update. `UserResponse`
carries `suspendedAt`, and the admin user list filters on `suspended`.

**Two idempotent endpoints.** `PUT /api/v1/users/{id}/suspension` suspends and `DELETE` on the same path lifts it,
both `hasRole('ADMIN')`. `UserService.findSuspendable` applies the deletion rules: administrators suspend regular
users, only the owner suspends administrators (`owner-required`, with the role read from the database), nobody
suspends the owner (`409 owner-protected`) or their own account (`400 own-account`). A change is audited as
`admin.user_suspended` or `admin.user_unsuspended`; repeating it changes nothing and records nothing.

**Suspending ends every session.** `UserService.suspend` raises the session version and publishes
`SessionsInvalidated`, the same event as a password change: in the same transaction the refresh tokens, every personal
access token (`auth.access_tokens_revoked`), pending password-reset links and a pending email change are deleted, and
after the commit Redis rejects access JWTs issued before it. Lifting the suspension restores none of them; the user
logs in again.

**Logins refused after the password.** `AuthService` refuses a suspended user's password login, second-factor step and
GitHub/Google login with `403 account-suspended` and an `auth.login_suspended` event naming the method. The check runs
only after the credentials are correct, so the answer does not reveal to anyone else that the account exists.

**Checked again where tokens and events are used.** `AccessTokenService` drops a token whose user is suspended, as a
second line behind their deletion. `WebhookEndpointRepository` skips endpoints of suspended owners when an event is
published, and `WebhookDispatcher` marks a queued delivery failed if its owner was suspended meanwhile. The endpoints
themselves are kept.

**Not a deletion.** Notes, endpoints, preferences and the audit trail stay; no `UserDeleted` event is published, so
no `user.deleted` webhook goes out and queued emails are not removed. See
[Security](../../guides/security/#suspending-a-user) and the
[runbook](../../guides/runbooks/#compromised-or-abusive-account).

## Consequences

- Owner broadcasts skip suspended users, but account emails do not: a password reset can be requested and
  completed, though the login stays refused.
- There is no reason field or expiry; a timed or explained suspension would need new columns and a job to lift it.
- Suspension is only an administrator action; locking accounts automatically (fraud signals, failed payments) would
  build on the same column.
