# Changelog

Notable changes to this template. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
releases use [semantic versioning](https://semver.org/). Each release's full list of pull requests is in its
[GitHub release notes](https://github.com/hikmethankolay/spring-boot-backend-template/releases).

## [Unreleased]

### Added

- `LOGIN_MFA_MAX_ATTEMPTS` (10): wrong two-factor codes at login are counted per account under their own key and lock
  it for `LOGIN_LOCK_DURATION` (`429` `urn:problem:login-locked`), on top of the IP and account limits. A block by
  this limit does not spend the IP and account attempts.
- `NOTES_MAX_PER_USER` (1000): creating a note beyond it gives `409` `urn:problem:note-limit-reached`. The cap is
  exact under concurrent requests and bounds the size of a user's data export.
- The personal data export has a `knownDevices` section (device, network, first and last seen), and its schema now
  lists the `webhooks` section it already had.
- `DB_MIGRATE_LOCK_TIMEOUT` (5s): the `migrate` profile gives up on a table lock after it, so a migration waiting
  behind open transactions fails and can be retried instead of queueing every request on the table.
- Migration `V22__audit_purge_batches_and_user_search_indexes.sql`: audit retention deletes in batches, and the
  administrators' user list gets indexes for its sort columns and, where `pg_trgm` can be created, trigram indexes for
  its search. A schema owner without the right to create extensions still migrates; the search then stays unindexed.

### Changed

- `PATCH /api/v1/users/{id}` on the caller's own id gives `400` `urn:problem:own-account`: the owner changes their own
  account through `/api/v1/users/me`, which asks for the current password and confirms a new email address.
- `PATCH /api/v1/users/me` with an email address that has an account no longer answers `409`; the confirmation link
  sent to it answers `409` `urn:problem:email-taken` when opened, so the endpoint does not tell which addresses are
  registered.
- Security events show `"admin"` as `actorId` when someone other than the user acted, instead of that user's id.
- `POST /api/v1/webhooks/{id}/deliveries/{deliveryId}/retry` on a delivery that is queued or being sent gives `409`
  `urn:problem:webhook-delivery-pending`.
- With `WEBHOOKS_REQUIRE_HTTPS=true` (the `prod` profile), a webhook URL must use the default port 443, which is the
  port the Kubernetes NetworkPolicy opens.
- `FLYWAY_BASELINE_ON_MIGRATE` defaults to `false`: set it to `true` once to adopt a database created before Flyway.
  Databases already migrated are not affected.
- A verification, password-reset or security-alert email that is given up loses its body, which held a one-time link;
  re-queuing such a dead letter drops it, and the user requests the email again. Optional emails re-queue as before
  (see the EmailsDropped runbook).
- The email outbox sends while emails are due, up to one poll interval per run, instead of at most 50 per poll.
- Kubernetes: the Deployment sets `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`, so the number of password hashes in
  parallel, each about 19 MiB, follows the pod's CPU instead of the node's. The migration Job's pods carry
  `app.kubernetes.io/component: migrate` instead of the app's name label, so the HPA, PDB and topology spread no longer
  count them, and have their own NetworkPolicy (DNS and PostgreSQL only).

### Fixed

- Webhooks:
  - A batch of slow sends handed out leases already in the past, so another instance sent the same delivery again;
    each attempt now leases and backs off from its own time.
  - A receiver answering a byte at a time could hold a sender for good: every request now has a hard deadline of
    `WEBHOOKS_CONNECT_TIMEOUT` plus `WEBHOOKS_RESPONSE_TIMEOUT`, enforced by the sender's own scheduler.
  - Concurrent failures on one endpoint lost increments of its failure count, and an owner's change could undo an
    endpoint being turned off at the same moment.
  - URLs were resolved inside a database transaction.
  - The per-user endpoint limit could be passed by parallel registrations.
  - The retry span was documented as about three days; it is about 2 days and 7 hours.
- An audited request held two database connections at once; the audit row is now written on the request's own
  connection behind a savepoint, so a failed insert still cannot fail the request.
- Password hashing and the breached-password lookup ran inside database transactions (sign-up, password change and
  reset, administrator update).
- Audit retention deleted a year of events in one statement, which could outlast `DB_STATEMENT_TIMEOUT` and roll back
  every night.
- Emails queued for a user were still sent after the account was deleted.
- Parallel requests could pass the cooldown of password-reset and verification emails, record a known device twice
  (and send two new-login alerts), or create more personal access tokens than `ACCESS_TOKENS_MAX_PER_USER`.
- A `page` far past the end of a list gave `500`; it now gives `400` `urn:problem:page-too-large`.
- Login lockout used a Lua script over two keys, which fails on Redis Cluster.
- Broadcasts ran one preference query per recipient, and the outbox gauges counted the whole queue on every scrape.
- Kubernetes: Kustomize's `includeSelectors` added the app's label to the NetworkPolicy's DNS selector, which then
  matched no DNS pod.

### Security

- Refresh-token reuse detection could be switched off: a thief who refreshed twice erased the victim's old token, so
  its replay no longer ended the session. Used tokens are now kept until they expire.
- Wrong two-factor codes at login had only the account limit of 100 an hour (see `LOGIN_MFA_MAX_ATTEMPTS` above).
- The owner could change their own email address or password through the administrators' endpoint without the
  current password or a confirmation link.
- A personal access token created while the password was being changed could outlive the change, and an access token
  minted while a session was ending could escape its revocation.
- Responses holding a new personal access token or webhook signing secret were kept in Redis for 24 hours by the
  idempotency filter. A slow idempotent request could also release another request's claim on its key.
- A JSON body was read in full before validation, so a few large requests could exhaust the heap; bodies are capped at
  1 MiB.
- SMTP error text, which can hold recipient addresses, is no longer written to the log.

## [5.0.0] - 2026-11-03

### Added

- Personal access tokens for scripts and integrations: `POST/GET /api/v1/users/me/access-tokens` and
  `DELETE /api/v1/users/me/access-tokens/{id}`. A `pat_…` token is sent as a bearer token, acts with its user's
  current role (capped by `ACCESS_TOKENS_MAX_ROLE`) and a `READ` or `WRITE` scope, cannot call account self-service
  under `/api/v1/users/me/**`, expires (`ACCESS_TOKENS_MAX_LIFETIME`) and is deleted when the user's sessions end.
  Creating one needs the password, emails a security alert and is audited. Migration `V20__access_tokens.sql`;
  settings `ACCESS_TOKENS_ENABLED`, `ACCESS_TOKENS_MAX_ROLE`, `ACCESS_TOKENS_ALLOW_WRITE`, `ACCESS_TOKENS_MAX_LIFETIME`
  and `ACCESS_TOKENS_MAX_PER_USER`; [ADR 10](docs/src/content/docs/adr/0010-personal-access-tokens.md).
- Outbound webhooks signed per Standard Webhooks: `/api/v1/webhooks` registers endpoints for `note.*` events (and
  `user.registered` / `user.deleted` for administrators), rotates secrets, sends test events and shows a delivery log
  with retries. Deliveries go through an outbox (`V21__webhooks.sql`), are retried with backoff for about three days
  and turn an endpoint off on `410 Gone` or repeated failures. Every connection resolves through an SSRF guard that
  refuses non-public addresses. Features publish events with `WebhookEvents.publish`. New dependency: Apache
  HttpClient 5 (managed by Spring Boot); settings `WEBHOOKS_*` (the `prod` profile requires HTTPS and its startup check
  warns about private addresses). **New required secret `WEBHOOK_ENCRYPTION_KEY`** (while webhooks are enabled):
  signing secrets are encrypted under their own key, separate from `MFA_ENCRYPTION_KEY`; `scripts/setup.sh` adds it to
  an existing `.env`, and deployments must add it to their secrets (or set `WEBHOOKS_ENABLED=false`);
  [guide](docs/src/content/docs/guides/webhooks.md),
  [ADR 11](docs/src/content/docs/adr/0011-webhooks.md).

### Fixed

- The release workflow skipped the SLSA provenance on the tag run, because its jobs skipped there (test, build) failed
  the provenance job's implicit status check; 4.3.0 was released without `sbom.spdx.json.intoto.jsonl`.

## [4.3.0] - 2026-11-02

### Added

- `DELETE /api/v1/users/me/sessions` signs out every session except the current one: their refresh tokens stop
  working and their access tokens are rejected at once (`auth.other_sessions_revoked`).
- `POST /api/v1/users/me/mfa/totp/recovery-codes` replaces all recovery codes with ten new ones, shown once. It needs
  the password and an authenticator code, counts failures as failed logins and sends a security alert.
- `GET /api/v1/users/me/social-accounts` lists the linked GitHub and Google accounts, and
  `DELETE /api/v1/users/me/social-accounts/{id}` unlinks one after the password, with an audit event and a security
  alert (`urn:problem:social-account-not-found` for unknown ids).
- `GET /api/v1/users` filters: `q` (case-insensitive part of the username or email, `%` and `_` matched literally, at
  most 254 characters), `role` and `suspended`.
- Optimistic concurrency over HTTP: `GET /api/v1/notes/{id}` and `GET /api/v1/users/me` return a strong `ETag` (the
  row version) and answer `If-None-Match` with `304`; `PUT /api/v1/notes/{id}` and `PATCH /api/v1/users/me` accept
  `If-Match` and refuse an outdated one with `412` `urn:problem:precondition-failed`. Requests without `If-Match`
  behave as before.
- `MFA_ENCRYPTION_KEY` takes a comma-separated list, like `JWT_SECRET`: the first key encrypts two-factor secrets
  and signs unsubscribe links, every key decrypts and verifies, and a secret under an older key moves to the first
  on its next use. See "Rotating MFA_ENCRYPTION_KEY" in the Security guide.
- Creating the owner account from `ADMIN_*` at startup is audited as `admin.owner_bootstrapped`.
- The prod profile logs a startup warning for each weakened setting: a remote database without
  `sslmode=verify-full`/`verify-ca`, Redis without a password or, when remote, without TLS, `COOKIE_SECURE=false`,
  `SWAGGER_ENABLED=true`, `RATE_LIMIT_ENABLED=false`, `REQUIRE_VERIFIED_EMAIL=false`, `MAIL_STARTTLS_REQUIRED=false`
  and `FLYWAY_ENABLED=true`. The app still starts.
- `security_fail_open_total{control}` counts requests the rate limit, token revocation, sign-up link reuse check and
  sign-up email cooldown let through while Redis was unavailable, with a `SecurityControlFailingOpen` alert;
  `client_address_not_public_total` (prod) warns once when a proxy hides client addresses (Production guide,
  "Client IP behind a proxy"); and a `ContainerOOMKilled` alert (needs kube-state-metrics).
- Runbooks for leaked secrets (database, JWT key, SMTP, OAuth, `MFA_ENCRYPTION_KEY`) and a point-in-time restore
  drill.

### Changed

- **Database (V18, V19):** emails given up after `MAIL_MAX_ATTEMPTS` stay in `email_outbox` as dead letters
  (`failed_at`) for `MAIL_DEAD_LETTER_RETENTION` (default 7 days) instead of being deleted, and can be queued again
  (Runbooks, EmailsDropped). Broadcasts record their progress (`last_user_id`); a broadcast unfinished at upgrade
  time is closed as partial. The schema now enforces what the application always did: emails in lower case and
  usernames without `@`. Existing mixed-case emails are lowered unless another account has the lower-case address,
  which stops the migration (find them with `SELECT lower(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1`).
  The outbox poll and a user's security events are read from matching indexes. Both migrations lock the tables
  they change while they run.
- `GET /api/v1/users/me` reads the database instead of the user cache, so its `ETag` is never stale.
- A `408`, `409` or `429` answer to a request with an `Idempotency-Key` is no longer replayed for 24 hours: a retry
  with the same key runs again.
- Startup now refuses zero or negative token and session lifetimes, `LOGIN_LOCK_DURATION`, `MFA_CHALLENGE_TTL`,
  rate limit windows and mail durations, `JWT_REMEMBER_ME_MAX_SESSION_TTL` shorter than `JWT_REMEMBER_ME_TTL`, a blank
  `MAIL_APP_NAME` and a non-positive `MAIL_BROADCAST_BATCH_SIZE`. The `app.mail.*` settings read with `@Value` are now
  validated with the others (same names and defaults).
- A new broadcast is refused while any broadcast is unfinished; one stalled for five minutes is resumed from the last
  user it queued instead of blocking new ones for an hour.
- Java: `AppProperties.Mfa.encryptionKey()` returns a `List<String>`; a key containing a comma is now split.

### Fixed

- A response kept for an `Idempotency-Key` could stay in Redis forever: it was written, given its 24-hour TTL and
  its key extended in three separate calls, so a Redis error or a stopped instance in between left a key without a
  TTL, which Redis never evicts under `noeviction`. The three now run as one script.
- Paging a list sorted by a column with equal values (a note title, a user role) could show a row twice and skip
  another, because `LIMIT`/`OFFSET` has no order among ties. `SortAllowList` now ends every sort with `id`.
- A sign-up that failed after claiming its one-minute email cooldown (a database error) kept the cooldown, so a
  retry within the minute answered 202 and sent nothing. The cooldown is now released when the transaction does
  not commit.
- An error while sending an email right after its transaction committed (no free database connection) answered
  500 to a request whose changes were saved. It is now logged and the outbox sends the email on its next run.
- The scheduler now waits up to 25 seconds for a running job, such as an email being sent, when the app stops,
  instead of interrupting it and sending the email again after the restart.
- `ProductionSettingsCheck` let a placeholder `MAIL_FROM` through when it had a display name
  (`Acme <no-reply@example.com>`).
- The Kubernetes Deployment set `replicas: 2` next to the autoscaler, so every `kubectl apply` scaled the pods back
  to two. The autoscaler now owns the count.
- `scripts\load-test.bat` pointed k6 at Mailpit on `localhost`, which inside the container is the container, so the
  test's sign-ups always failed. It now uses `host.docker.internal`.
- The guides said `account/` could be deleted on its own; `auth/` calls it for sign-up. They also suggested
  migration numbers the template already uses, and `SECURITY.md` named 4.0 as the supported version.
- A release tag pinned the previous release's image: `scripts/release.sh` tagged the release commit before the image
  existed, and the digest was committed to `main` only afterwards, so deploying from `v4.2.2` ran the 4.2.1 image
  under the 4.2.2 name (the digest wins over the tag). The release workflow now makes the tag itself, on the commit
  that pins the published digest. `scripts/release.sh` pushes only the release commit and starts the workflow on
  `main` (with the GitHub CLI, `gh`, or by hand); the workflow tests, builds, pins, tags, then signs and releases
  from the tag, so signatures still name `release.yml@refs/tags/v<version>` and existing `ClusterImagePolicy`
  rules keep working. Stable tags pushed by hand are refused; pre-release tags still are released from a push.
  Tags `v4.2.2` and earlier keep their old digest; deploy those from `main`.
- An exception other than `MailException` while sending an email rolled the attempt back, so the same email was
  retried first forever; it now counts as a failed attempt.
- A broadcast cut off by a crash or shutdown stayed unfinished and was never resumed, and an error could skip marking
  it finished.
- Validation problems also list class-level and cross-field errors (with an empty `field`), and invalid query or path
  parameters get `urn:problem:validation-failed` instead of Spring's generic answer.
- The guides now match the code on the ArchUnit rules, every frontend page emails link to, the dependencies on
  `mail`, `urn:problem:own-account` and `REQUIRE_VERIFIED_EMAIL`, and document the trade-offs of per-account lockout,
  concurrent refreshes, the user cache, audit events kept after account deletion and revocations lost while Redis is
  down (ADR 2).

### Security

- A login with an unknown identifier, often a password typed into the wrong field, used the identifier as part of
  a Redis key for its lockout counter. The key now holds a hash of it, like the audit log already did.

## [4.2.2] - 2026-10-31

### Fixed

- The documentation site of 4.2.0 and 4.2.1 was not published: its mutation testing step failed because the 4.2
  security code lowered the mutation score of `security` and `auth` to 94%, below the 95% gate. Tests now cover the
  password confirmation that cannot be checked, the refused logins of suspended users and where `Idempotency-Key` is
  documented (96%). The `Rename project` CI job failed on a new architecture rule that named the template's package;
  the rule now uses the project's own package. No change to the application.

## [4.2.1] - 2026-10-31

### Security

- A pending email change survived the owner taking the account back: someone with the password could ask to move
  the account to their own address and open the link after the owner changed or reset the password. Ending every
  session (password change or reset, role change, suspension) now cancels the pending change and its link, and asking
  for a new change revokes the earlier link in the same transaction.
- `Idempotency-Key` kept the answers of the two-factor enrollment endpoints, with the TOTP secret and the recovery
  codes, in plain text in Redis for 24 hours. `/api/v1/users/me/mfa/**` is no longer covered (the header is ignored
  and no longer documented there).
- `IdempotencyFilter` read the whole request body into memory. Bodies over 1 MiB now run without the key instead.

## [4.2.0] - 2026-10-31

### Added

- Suspending users: `PUT` and `DELETE /api/v1/users/{id}/suspension`. A suspended user's sessions end at once and
  every login (password, second factor, GitHub/Google) is refused with `403 account-suspended`, after the password so
  it reveals nothing; the data is kept. Administrators suspend regular users, the owner also administrators. Users get
  `suspendedAt`. Migration `V16__user_suspension.sql` (a nullable column).
- Security activity from the audit log: `GET /api/v1/users/me/security-events` for users and
  `GET /api/v1/users/{id}/security-events` for administrators (also for deleted users), paginated, newest first. The
  events are also in the personal data export (`securityEvents`).
- `Idempotency-Key` for signed-in `POST` requests: a retry with the same key within 24 hours gets the first response
  again (`Idempotent-Replayed: true`) instead of running twice; another request with the same key gets `422`, a retry
  while the first runs `409`. Kept in Redis; ignored without it. A key is held for a running request for at most one
  minute. See the API overview.
- Runbook for a compromised or abusive account: read its security events, then suspend it.

### Security

- Confirming a change with the current password had no lockout outside the login, turning off two-factor
  authentication and broadcasts. With a stolen access token, the password could be guessed at the rate limit
  (120 requests a minute) through changing the password or email, deleting the account, turning two-factor
  authentication on, or the owner's role change and administrator deletion, and the account then taken over. Wrong
  passwords there now count as failed logins of the IP and the account (`429` when blocked, `503` without Redis).
  New `LoginAttemptService.checkReentered`; `BroadcastService.send` no longer takes the client IP.
- `POST /api/v1/auth/password-reset` and `POST /api/v1/auth/verify-email/resend` sent their email over SMTP before
  answering, but only for addresses with an account, so the response time told which addresses are registered. The
  email is now left to the outbox poller and arrives within `MAIL_OUTBOX_POLL_INTERVAL` (10 seconds by default).
  New `EmailOutbox.enqueueDeferred`.
- Two concurrent requests with the same password-reset or verification link could both use it, setting two different
  passwords. The token row is now locked while it is redeemed.

### Fixed

- The Javadoc check, and with it the CI Documentation job and the 4.1.0 documentation site build, failed on an
  undocumented constructor parameter of `SocialAccountService`.
- A server error during a login (for example, no free database connection) counted as a failed attempt, so after an
  outage a busy IP could stay locked out for `LOGIN_LOCK_DURATION`. A failed user lookup was also answered
  `401 Invalid username or password` instead of `500`. The same counting applied to the MFA login, turning off
  two-factor authentication and sending a broadcast. New `LoginAttemptService.giveBack`.
- Load test: the CSRF guard answered `403` to refresh, logout and the second login in setup (cookies without a Bearer
  token), and a failed journey started again at once, turning an overloaded app into a retry storm.

### Changed

- **Changing your own email waits for confirmation.** `PATCH /api/v1/users/me` with a new email no longer switches the
  address: it is returned as `pendingEmail` and gets a link to the existing `/verify-email` page; opening it makes it
  the account's address, already verified, and tells the old one. Until then logins and password resets keep using the
  old address, so a mistyped address cannot take over the account's mail. Frontends that show `email` after the
  `PATCH` should show `pendingEmail` instead. An administrator's change is still immediate. Migration
  `V17__email_change_confirmation.sql` (a nullable, partially indexed column and the `EMAIL_CHANGE` token type).
  `POST /api/v1/auth/verify-email/resend` with the new address sends a lost link again.
- Kubernetes: `terminationGracePeriodSeconds` raised from 45 to 70 seconds. The preStop pause (10s), graceful shutdown
  (30s) and the task executor's wait for a broadcast being queued (25s) could add up to 65, and the pod was killed
  before the broadcast was fully queued.
- Docker image: `-XX:+ExitOnOutOfMemoryError`, so a JVM that ran out of heap exits and is restarted instead of running
  on half-broken (its liveness probe is on the separate management port and could stay up).
- Testing guide: load test results on 2 CPUs and 4 GB, explained, in place of the developer-machine numbers, and when
  to raise the login lockout for a load test. Production guide: a Sizing section.
- `scripts/smoke.sh` and `scripts\smoke.bat` also read and change the email preferences.
- README: a full features table, the 4.1 features and a new recording of the first run.

## [4.1.0] - 2026-10-27

### Added

- HTML emails: `MailRenderer` renders every email from a Thymeleaf template pair in `src/main/resources/mail/` (a
  shared layout and a plain-text twin), and the outbox sends both as `multipart/alternative`. Migration `V13` adds
  `email_outbox.html_body`. New Email templates guide and ADR 7.
- Email categories and preferences: `MailCategory` marks emails as transactional or optional. Optional ones carry an
  unsubscribe footer and one-click `List-Unsubscribe` headers (RFC 8058), and are skipped for users who turned them
  off. New endpoints `GET`/`PUT /api/v1/users/me/email-preferences` and `GET`/`POST /api/v1/mail/unsubscribe`
  (+ `/all`), problem type `unsubscribe-token-invalid`, audit event `user.email_preferences_changed`, metrics
  `mail_outbox_suppressed_total` and `mail_unsubscribe_total`. Migration `V14` adds `email_preferences` and
  `email_outbox.unsubscribe_token`. ADR 8.
- `MAIL_UNSUBSCRIBE_BASE_URL`: the API's public URL for the unsubscribe header. The `prod` profile refuses to start
  while it points to localhost, so existing deployments must set it.
- Security alert emails: a sign-in from a new device, and changes to the password, email address (to the old
  address), two-factor authentication and linked accounts. A device is recognized by an OWASP-style device cookie
  set at login, or, without it, by browser family and public network. Settings `MAIL_SECURITY_ALERTS_ENABLED`,
  `MAIL_NEW_LOGIN_ALERT_ENABLED`, `MAIL_NEW_LOGIN_LOOKBACK`; metric `mail_security_alerts_total`. ADR 9.
- Owner broadcasts: `POST /api/v1/admin/mail/broadcasts/preview`, `POST` and `GET /api/v1/admin/mail/broadcasts`
  (owner and password, optional categories only, queued in the background), problem type `broadcast-in-progress`,
  audit event `mail.broadcast_sent`, metrics `mail_outbox_broadcast_pending` and `mail_broadcast_recipients_total`.
- `MAIL_APP_NAME`: the name shown in every email, by default the application name.
- Migration `V15` adds `known_devices`, `mail_broadcasts` and `email_outbox.broadcast_id`.

### Changed

- `EmailOutbox` sends through a `JavaMailSender` (Spring Boot's mail auto-configuration provides one).
  `enqueue(to, subject, text)` still sends plain text.
- The outbox sends broadcast emails after every other email due, and `mail_outbox_pending` (with the
  `EmailBacklog` alert) no longer counts them.
- Login responses also set a `device` cookie (HttpOnly, `/api/v1/auth`, one year).
- Background work runs on Spring Boot's task executor, which now waits up to 25 seconds for it at shutdown.

## [4.0.3] - 2026-10-26

### Changed

- Releases are signed with cosign 3, in the Sigstore bundle format (the image signature is an OCI referrer, the SBOM
  bundle uses the new format). Verify them with cosign 3, or cosign 2.6 with `--new-bundle-format`.
- `deploy/kubernetes/policy/` accepts both formats, so rollbacks to earlier releases are still admitted. It needs the
  Sigstore policy-controller v0.15.0 or later.

## [4.0.2] - 2026-10-26

### Added

- `scripts/release.sh <version>` (`scripts\release.bat` on Windows) makes the release commit (CHANGELOG, the image
  tag in `deploy/kubernetes`, the version in the guides' examples) and the tag, and pushes them after asking.
- The release workflow commits the published image's digest to `deploy/kubernetes` on `main`, so the manifests no
  longer need it set by hand.

### Changed

- GitHub Actions: setup-java 6, setup-node 7, upload-artifact 7, and the Docker actions setup-qemu 4, setup-buildx
  4, metadata 6 and build-push 7 (all on Node 24). cosign-installer 4, still installing cosign 2: cosign 3 signs in a
  new bundle format by default, which the Sigstore policy-controller and cosign 2 verifiers may not read.
- Maven 3.10.0 with Maven Wrapper 3.3.4; astro 7.4.2 for the documentation site.
- `compose.yaml`: Mailpit v1.32, Prometheus v3.15.1 and Grafana 13.2.4.

### Fixed

- `SECURITY.md` listed 3.2.x as the supported version, and the README gave Kubernetes 1.25 as the oldest that works
  (4.0.0 needs 1.30).

## [4.0.1] - 2026-10-26

### Fixed

- The documentation site of 4.0.0 was not published: a leftover `@return` in the Javadoc of the register endpoint
  failed the Javadoc build.
- `scripts/rename-project.sh` deletes `.gitleaksignore`, whose entries apply only to this repository's history, so
  the template's references to itself no longer remain in a renamed project.

### Changed

- json-schema-validator 3.0.8 (used by the tests), Error Prone 2.51.0 and JaCoCo 0.8.15.

## [4.0.0] - 2026-10-26

A security release. Every item marked **Breaking** needs a change in clients or deployments; the guides describe
each one.

### Added

- Email-first sign-up: `POST /api/v1/auth/register` takes only `{"email"}` and always answers `202`. A new address
  gets a link to finish the sign-up and a registered one gets a notice. `POST /api/v1/auth/register/complete` takes
  the link's token with the username and password and creates the account, already verified. Nothing is stored
  before the link is opened, so the response, its timing and the usernames do not show which addresses have
  accounts. One email per address per minute; each link creates at most one account.
- CSRF guard for cookie-authenticated requests: a state-changing request with an auth cookie and no Bearer header
  needs an allowed `Origin` or `X-Requested-With: XMLHttpRequest`, otherwise `403 urn:problem:cross-site-request`.
- Per-route rate limits on top of the global one (`app.rate-limit.routes`):
  - register: 5 per hour;
  - login: 30 per minute;
  - refresh: 60 per minute;
  - password reset: 5 per hour.
- An absolute session lifetime: 7 days (`app.jwt.max-session-ttl`), or 90 with remember-me, however often the
  session is refreshed.
- Passwords are checked against a bundled list of common passwords. Have I Been Pwned can also be checked
  (`PWNED_CHECK_ENABLED`).
- Security events are also stored in an append-only `audit_events` table (`V12`):
  - only the table owner may change or remove rows;
  - `AUDIT_RETENTION` (default `365d`) purges old rows daily and always keeps the last 90 days.
- CI scans for secrets with gitleaks, runs a ZAP baseline scan against the running app, and Dependabot updates the
  `compose.yaml` images.
- Kubernetes:
  - an egress `NetworkPolicy`;
  - a migration Job (`migrate-job.yaml`, `migrate` profile);
  - a Sigstore `ClusterImagePolicy` that admits only images signed by the release workflow;
  - the image is pinned by tag and digest.
- A warning at startup when a development setup points at a remote database.

### Changed

- **Breaking:** the minimum password length is 15 characters, or 8 with two-factor authentication, with no
  composition rules (NIST SP 800-63B-4).
  - `ADMIN_PASSWORD` needs 15 characters.
  - Two-factor authentication cannot be turned off while the password is shorter than 15 characters.
- **Breaking:** passwords are hashed with Argon2id (19 MiB, t=2, p=1). BCrypt hashes still match and are rewritten
  at the next login. After a rollback to 3.2.x, users with an Argon2id hash must reset their password.
- **Breaking:** sign-up is two steps, see *Added*. Frontends need a `/sign-up?token=...` page.
  `REQUIRE_VERIFIED_EMAIL` now only applies to changed addresses.
- **Breaking:** access tokens are `typ: at+jwt` with an `aud` claim (`JWT_AUDIENCE`, RFC 9068).
  - Tokens issued before 4.0 are rejected, so clients refresh once.
  - Services that verify tokens through the JWKS must accept `at+jwt`.
- **Breaking:** `JWT_SECRET` needs at least 43 characters.
- **Breaking:** with `app.cookie.secure=true` the cookies are named `__Host-auth_token` and
  `__Secure-refresh_token`, so every browser logs in once more.
- **Breaking:** `POST /api/v1/auth/oauth2/{provider}` requires `codeVerifier` (PKCE).
- **Breaking:** the image runs a jlink-trimmed JRE on distroless `java-base-debian13`: 196 MB instead of 335 MB. It
  has no shell, so it has no `HEALTHCHECK`.
- **Breaking:** Kubernetes 1.30 or later is required, for the `preStop` sleep action.
  - Secrets are mounted as files at `/etc/app/secrets` instead of environment variables.
  - Pods run with `FLYWAY_ENABLED=false`; run `migrate-job.yaml` with `app-migrator-secrets` before each rollout.
  - The *Production* guide has the SQL for both database roles.
- The MFA encryption key is derived with HKDF-SHA256. Values stored before 4.0 still decrypt.
- Spring Boot 4.1.2 and springdoc 3.2.0. The Tomcat and Jackson version overrides are gone.
- The coverage minimums are now:
  - 95% of lines;
  - 90% of branches;
  - 90% of lines in `auth` and `security`.

## [3.2.9] - 2026-10-24

### Changed

- Logout no longer stores the presented access token's id in Redis: ending its session (since 3.2.8) already
  rejects it, so the separate entry changed nothing.

### Fixed

- The documentation site of 3.2.8 was not published: that redundancy lowered the mutation score below the 95% gate.

## [3.2.8] - 2026-10-24

### Fixed

- A rolling update to 3.2.x, or a rollback from it, broke login and refresh on the pods still running the previous
  version: `V9` made `refresh_tokens.session_started_at` NOT NULL without a default, which their inserts lack.
  `V10` adds the default, and a new test fails on any migration that makes a column NOT NULL without one.
- The email outbox sent its due emails in one transaction. A slow or unreachable SMTP server kept it idle until
  PostgreSQL ended it (`DB_IDLE_IN_TRANSACTION_TIMEOUT`), so emails already sent were sent again and failures were
  never counted. Each email now has its own transaction.
- The per-IP rate limit and login lockout counted every IPv6 address separately, so a client with a `/64` could use a
  new address per request. IPv6 clients now count as their `/64`.
- Logout now ends the whole session of the presented access token, also without the refresh token, and revokes the
  session's older access tokens.
- The owner account created from `ADMIN_*` skipped the password rule; the application now refuses to start with
  credentials a registration would reject. `setup` always generates a password that passes it (one in about fifteen
  had no digit).
- Mail is sent over STARTTLS whenever the server offers it, and the prod profile requires it
  (`MAIL_STARTTLS_REQUIRED`, default `true`): port 587 sent the mail and the SMTP password unencrypted, or failed at
  providers that demand STARTTLS.
- `scripts\db.bat` ran every command for any argument, so `dev.bat` hung in `logs -f`; `scripts\smoke.bat` failed
  before its first request; `scripts\site.bat` built the site after a failed copy.
- `scripts/setup.sh` and the other scripts rejected a valid JDK when `JAVA_TOOL_OPTIONS` was set, and
  `scripts/test.sh` failed without arguments on macOS's bash 3.2.
- `scripts/docker.sh up` failed when `PORT` in `.env` was not 8080.
- The load test's ramp-up stages started at full load.
- A pre-release tag published its image as `latest`.

## [3.2.5] - 2026-10-24

### Changed

- `scripts/client.sh` generates the API client with OpenAPI Generator instead of openapi-typescript: a TypeScript
  client (`typescript-fetch`, one class per tag on the browser's `fetch`) by default, or one in another language
  (Kotlin, Swift, Dart, Java, Python, ...) with `--generator <name>`. It runs in the generator's container image, so
  it needs Docker or Podman instead of Node.js. The argument is now an output directory (default
  `target/client/<generator>`), and code using openapi-fetch must move to the generated classes; *Connecting a
  frontend* shows login, refresh and errors with them. CI generates the TypeScript client and type-checks it on every
  push.

## [3.2.1] - 2026-10-24

### Fixed

- The CI job that tests `rename-project` failed on its own workflow file, and in a project made from the template it
  would have run on the already renamed project and failed on every push. It now runs only in the template
  repository, and `rename-project` leaves `ci.yml` alone.

## [3.2.0] - 2026-10-24

### Added

- Session list: `GET /api/v1/users/me/sessions` shows each active session with its start, last use, `User-Agent` and
  IP, and marks the current one; `DELETE /api/v1/users/me/sessions/{id}` logs that device out at once (refresh tokens
  deleted, access tokens rejected). Access tokens carry the session id as a new `sid` claim. Migration
  `V9__session_details.sql` adds the columns; the data export lists them too.
- `setup` works without a JDK when Docker or Podman is installed: it creates `.env`, skips compiling and points to
  `scripts/docker.sh up`, so the template can be tried with Docker alone.
- `rename-project` also points the repository links to your GitHub repository (read from `origin`, or
  `--repo owner/name`): the image in `deploy/kubernetes`, `CODEOWNERS`, `SECURITY.md`, the issue templates, the guides
  and the documentation site. It stops before changing anything if `origin` is still the template. CI renames a
  checkout and runs the unit tests on every push.
- `KubernetesManifestTest` fails when the image tag in `deploy/kubernetes/kustomization.yaml` lags behind the latest
  release in `CHANGELOG.md`.
- [Compared with other starters](https://hikmethankolay.github.io/spring-boot-backend-template/guides/comparison/):
  when to use this template, Spring Initializr, JHipster or Bootify.

### Changed

- The `prod` profile also refuses to start while `MAIL_HOST`/`MAIL_PORT` point to the local Mailpit
  (`localhost:1025`) or `MAIL_FROM` is an `example.com`, `example.net` or `example.org` address: verification emails
  would never arrive and nobody could finish signing up. Existing deployments must set both before upgrading;
  `deploy/kubernetes/configmap.yaml` needs your own `MAIL_FROM`.
- Pages, CodeQL and Scorecard skip themselves in repositories made from the template until they can work there: the
  documentation site until Pages is turned on, code scanning in private repositories (see *Using the template*).

### Fixed

- `deploy/kubernetes/kustomization.yaml` deployed image `2.9.1`; it now pins the latest release.
- The documentation site's version step failed in a repository without release tags.
- Spring MVC's own errors (malformed JSON, a path or query value of the wrong type such as `/api/v1/notes/abc`, an
  unsupported method or media type) had no `type`, although the API documents `about:blank` for them; Spring 7 leaves
  it out. They now carry it.

### Security

- A reused refresh token also revokes the access tokens of its session, instead of leaving them valid until they
  expire.
- Ending a session waits for a refresh of that session in flight, so the token the refresh issues is deleted too
  instead of keeping the session alive.

## [3.1.0] - 2026-10-24

### Added

- The `prod` profile refuses to start with the development defaults: `CORS_ALLOWED_ORIGINS`, `MAIL_LINK_BASE_URL`
  or, with GitHub or Google login on, `OAUTH2_REDIRECT_URI` pointing to localhost, or `DB_PASSWORD=app`. The error
  names every variable to set.
- [Connecting a frontend](https://hikmethankolay.github.io/spring-boot-backend-template/guides/frontend/): the pages a
  frontend needs, cookies or Bearer tokens, the two-factor login step, refreshing across tabs and error handling.
- Runbook for rolling back a release: which migrations are safe to roll back, and expand/contract for the others.
- Steps for removing two-factor authentication or social login from a project (*Using the template*), each tried
  out with the full test suite.
- `smoke` also creates, edits and deletes a note, and deletes its account at the end, so repeated runs leave no users
  behind.
- A banner, an animated demo of the first run and an *Open in GitHub Codespaces* button in the README; the banner
  (`.github/social-preview.png`) doubles as the repository's social preview image.

### Changed

- Existing deployments with `SPRING_PROFILES_ACTIVE=prod` must set `CORS_ALLOWED_ORIGINS` and `MAIL_LINK_BASE_URL` to
  their frontend (and `OAUTH2_REDIRECT_URI` when GitHub or Google login is on) and use a database password other than
  `app` before upgrading; the application refuses to start otherwise.

### Fixed

- The Kubernetes guide creates the owner account (`ADMIN_*` in the secret), and the ConfigMap sets
  `OAUTH2_REDIRECT_URI`.
- *Using the template* named `V3__…` as the next migration (it is `V9`) and left two-factor authentication and social
  login out of the project structure; the documentation home page did not mention them either.
- The hint for updating `api/openapi.json` in `OpenApiSpecExportIntegrationTest` used `-Dtest=`, which runs the class
  with the unit tests and then every integration test; it now says `-Dit.test=` like the guides.

## [3.0.1] - 2026-10-24

### Security

- Wrong passwords and codes when turning off two-factor authentication (`DELETE /api/v1/users/me/mfa/totp`) count as
  failed logins, so a stolen access token cannot be used to guess the code there without running into the lockout.

### Fixed

- The Javadoc build, and with it CI and the documentation site, failed on a stale link in `MfaService`.

## [3.0.0] - 2026-10-23

### Added

- Two-factor authentication with authenticator apps (TOTP): enrollment with a QR-code URI, ten single-use recovery
  codes, and a second login step (`202` + `mfaToken`, then `POST /api/v1/auth/login/mfa`). Secrets are encrypted
  with AES-GCM under the new, **required** `MFA_ENCRYPTION_KEY`; `setup` generates it.
- Login with GitHub and Google (`GET /api/v1/auth/oauth2/providers`, `POST /api/v1/auth/oauth2/{provider}`): the
  frontend runs the redirect with PKCE, the API exchanges the code and stays stateless. Enabled per provider by
  `GITHUB_CLIENT_ID`/`_SECRET` and `GOOGLE_CLIENT_ID`/`_SECRET`.
- `mfaEnabled` in the user response.
- A sample feature, `note/` (`/api/v1/notes`), with the guide *Your first feature*: copy it, then delete it.
- `scripts/client.sh`: TypeScript types for the API from `api/openapi.json` (openapi-typescript), for openapi-fetch.
- A dev container for VS Code and GitHub Codespaces: JDK 25, Docker and Node.js without installing anything.
- *Getting fixes from the template later* in *Using the template*.

### Changed

- Existing deployments must set `MFA_ENCRYPTION_KEY` (at least 32 random characters) before upgrading; the
  application refuses to start without it.

### Fixed

- The Java reference on the documentation site showed version `0.0.1-SNAPSHOT` instead of the release.

## [2.9.2] - 2026-10-21

### Added

- `LOGIN_ACCOUNT_MAX_ATTEMPTS` (default `100`): accounts get their own failed-login limit, higher than the per-IP
  `LOGIN_MAX_ATTEMPTS`, so a single client can no longer lock a real user out by guessing their password.
- CI runs the Windows scripts (`test.bat --unit`, `docs.bat`, `build.bat`) on `windows-latest`.

### Changed

- Login fails closed when Redis is unavailable: it answers `503` with `urn:problem:login-unavailable` instead of
  allowing logins without the lockout. Rate limiting and token revocation still fail open (ADR 2, amended).
- The Java reference is generated by Javadoc instead of Doxygen (ADR 6): no Doxygen or Perl to install, and
  `scripts/docs.sh` is always strict, so it no longer takes `--strict`.
- Integration tests fail instead of being skipped when no container runtime is available, so a green build means
  every test ran. `scripts/test.sh --unit` still runs the unit tests without one.
- The README shows fewer badges.

### Fixed

- Release images report the release version instead of `0.0.1-SNAPSHOT`.

## [2.9.1] - 2026-10-21

### Added

- Releases attach a Sigstore signature bundle and SLSA level 3 provenance for the SBOM.
- CodeQL, OpenSSF Scorecard and mutation testing badges.

### Changed

- The documentation site deploys with GitHub Pages actions; no workflow except the release needs write access to
  the repository.

### Fixed

- Documentation site build with Starlight 0.39+ (failed for 2.9.0).
- `postcss-selector-parser` in the documentation site (GHSA-rj75-hqrm-r3gf).

## [2.9.0] - 2026-10-21

### Added

- `JWT_ALGORITHM=RS256` signs with `JWT_PRIVATE_KEY` and publishes the public keys at `/.well-known/jwks.json`.
- Key rotation: `JWT_SECRET` and `JWT_PRIVATE_KEY` take a comma-separated list (the first signs, all verify), and
  tokens carry a `kid`. Tokens issued by 2.8 still verify.
- SLOs with burn-rate alerts, log-based alerts, a scheduled-job alert and a runbook for every alert.
- Build gates: Maven Enforcer, Error Prone, SpotBugs with FindSecBugs, Spotless (palantir-java-format) and JaCoCo
  coverage minimums. PIT mutation testing of `security` and `auth` runs weekly with a 95% threshold.
- CodeQL and OpenSSF Scorecard workflows; the release verifies its own signature and provenance and attaches the SBOM.
- `scripts/test.sh --unit` for the unit tests alone; integration tests are named `*IntegrationTest` and run in Failsafe.
- Architecture decision records, a compatibility table, a code of conduct and this changelog.

### Fixed

- Audit log key-value fields could carry line breaks into text log layouts.

## [2.8.2] - 2026-10-20

### Fixed

- Docker build failed on the Maven wrapper checksum (release 2.8.1 published no image; use 2.8.2).
- Account-takeover, session and lockout races found in a security audit.
- Lockout, startup, rate limit, release and logging follow-ups from the same audit. Rate-limit counters reset once
  on deploy because their Redis keys changed format.

[Unreleased]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v5.0.0...HEAD
[5.0.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.3.0...v5.0.0
[4.3.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.2.2...v4.3.0
[4.2.2]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.2.1...v4.2.2
[4.2.1]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.2.0...v4.2.1
[4.2.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.1.0...v4.2.0
[4.1.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.0.3...v4.1.0
[4.0.3]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.0.2...v4.0.3
[4.0.2]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.0.1...v4.0.2
[4.0.1]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.0.0...v4.0.1
[4.0.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.2.9...v4.0.0
[3.2.9]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.2.8...v3.2.9
[3.2.8]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.2.5...v3.2.8
[3.2.5]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.2.1...v3.2.5
[3.2.1]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.2.0...v3.2.1
[3.2.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.0.1...v3.1.0
[3.0.1]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v2.9.2...v3.0.0
[2.9.2]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v2.9.1...v2.9.2
[2.9.1]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v2.9.0...v2.9.1
[2.9.0]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v2.8.2...v2.9.0
[2.8.2]: https://github.com/hikmethankolay/spring-boot-backend-template/releases/tag/v2.8.2
