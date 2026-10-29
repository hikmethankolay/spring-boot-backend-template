# Changelog

Notable changes to this template. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
releases use [semantic versioning](https://semver.org/). Each release's full list of pull requests is in its
[GitHub release notes](https://github.com/hikmethankolay/spring-boot-backend-template/releases).

## [Unreleased]

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

- Testing guide: load test results on 2 CPUs and 4 GB, and when to raise the login lockout for a load test.
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

[Unreleased]: https://github.com/hikmethankolay/spring-boot-backend-template/compare/v4.1.0...HEAD
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
