# Spring Boot Backend Template

<img src=".github/social-preview.png" width="100%" alt="Spring Boot Backend Template: the secure, tested starting point for your next REST API. JWT with refresh rotation, two-factor authentication, GitHub and Google login, Redis rate limiting, signed images, Kubernetes-ready. Spring Boot 4.1, Java 25, PostgreSQL 18, Redis 8, MIT.">

[![CI](https://github.com/hikmethankolay/spring-boot-backend-template/actions/workflows/ci.yml/badge.svg)](https://github.com/hikmethankolay/spring-boot-backend-template/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://hikmethankolay.github.io/spring-boot-backend-template/badges/coverage.json)](https://hikmethankolay.github.io/spring-boot-backend-template/coverage/)
[![Mutation score](https://img.shields.io/endpoint?url=https://hikmethankolay.github.io/spring-boot-backend-template/badges/mutation.json)](https://github.com/hikmethankolay/spring-boot-backend-template/actions/workflows/mutation.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/hikmethankolay/spring-boot-backend-template/badge)](https://scorecard.dev/viewer/?uri=github.com/hikmethankolay/spring-boot-backend-template)
[![Release](https://img.shields.io/github/v/release/hikmethankolay/spring-boot-backend-template)](https://github.com/hikmethankolay/spring-boot-backend-template/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Start a new Spring Boot REST backend from a codebase that already handles the hard, repetitive parts:
secure login, user accounts, errors, caching, monitoring and deployment. All of it is tested and documented.

**[Read the documentation →](https://hikmethankolay.github.io/spring-boot-backend-template/)**

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/hikmethankolay/spring-boot-backend-template)

<img src="docs/public/demo.svg" width="760" alt="Terminal: scripts/setup.sh generates the secrets, scripts/dev.sh starts PostgreSQL, Redis, Mailpit and the app in about four seconds, and scripts/smoke.sh signs a user up through the emailed link, logs in, reads and changes the email preferences, creates, edits and deletes a note, revokes the session and deletes the account, with every status as expected.">

## Why use it

- **Authentication you don't have to write.** Short-lived JWTs with rotating refresh tokens and reuse detection,
  logout that really revokes, a list of signed-in devices users can log out, every session ended on a password
  change, email verification, password reset, two-factor authentication with authenticator apps, login with GitHub
  and Google, brute-force lockout, rate limiting, security headers and an audit log. Users are emailed when someone
  signs in from a new device or changes their password, email address or two-factor settings.
- **Email that looks finished.** HTML templates with a plain-text twin and a shared layout, sent through an outbox
  that survives SMTP outages, email preferences with RFC 8058 one-click unsubscribe, and owner broadcasts that never
  delay a password reset.
- **A worked example to copy.** The sample `note/` feature shows ownership checks, pagination, migrations, the data
  export and tests in a few small files, and `scripts/client.sh` generates a typed client for your frontend, or for
  your mobile app in its own language.
- **Production defaults out of the box.** One `prod` profile switches on JSON logs, secure cookies, required email
  verification and an internal metrics port. Migrations, Redis caching, an HTML email outbox, query timeouts,
  metrics with alert rules and a Grafana dashboard, tracing, account deletion and data export are already wired in.
- **Tested against the real thing.** 380+ tests, 170 of them against real PostgreSQL and Redis containers, alongside
  architecture rules, mutation testing, a load test and a vulnerability scan of the Docker image in CI.
- **Ready to ship.** Releases publish signed multi-arch images with an SBOM; Kubernetes manifests pass the
  `restricted` Pod Security Standard.
- **One command per task** on Linux, macOS and Windows: setup, dev, test, build, run, docker, docs and more.

Built with Spring Boot 4.1, Java 25, PostgreSQL 18 and Redis 8. Not sure it fits? See how it
[compares with Spring Initializr, JHipster and Bootify](https://hikmethankolay.github.io/spring-boot-backend-template/guides/comparison/).

### Why the newest versions

A template is copied once and then lives for years, so it starts on the newest releases: every project created from
it begins with the longest support window ahead instead of a migration. Java 25 is an LTS release; Spring Boot 4.1,
PostgreSQL 18 and Redis 8 are the current major versions. Dependabot proposes updates weekly.

### Compatibility

| Component | Used and tested | Oldest expected to work | Notes |
|-----------|-----------------|-------------------------|-------|
| JDK | 25 | 25 | Enforced by the build (Maven Enforcer) |
| Maven | 3.10.0 (wrapper) | 3.9 | Use `./mvnw` |
| Spring Boot | 4.1 | 4.1 | Spring Framework 7, Spring Security 7, Jakarta EE 11 |
| PostgreSQL | 18 | 15 | Plain SQL migrations; only 18 runs in CI |
| Redis | 8 | 7 | Plain commands and Lua scripts; only 8 runs in CI |
| Kubernetes | not run in CI | 1.30 | Stable APIs only; the `preStop` sleep action needs 1.30 |

Policy: patch and minor updates land as soon as the tests pass. A new major version of a component is adopted once it
is generally available and the tests pass, and is recorded in the
[changelog](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/CHANGELOG.md).

## Features

| Area | What you get |
|------|--------------|
| Sign-up and login | Email-first sign-up, login by username or email, "remember me", JWT access tokens (HS256 or RS256 with a JWK Set) and rotating refresh tokens with reuse detection, HttpOnly cookies or Bearer tokens |
| Account security | Two-factor authentication (TOTP) with recovery codes, GitHub and Google login with PKCE, session list with remote logout, every session ended on a password change, brute-force lockout per IP and account, breached-password check |
| Account flows | Email verification, password reset, email change with re-verification, account deletion and a personal data export (GDPR/KVKK) |
| Roles | `USER`, `ADMIN` and a single `OWNER`; role changes and administrator management need the owner and their password |
| Email | HTML templates with a plain-text twin and a shared layout, a transactional outbox with retries, snapshot tests of every template |
| Email preferences | Transactional and optional categories, a preferences endpoint, unsubscribe links and RFC 8058 one-click `List-Unsubscribe` headers, opt-outs honoured even for queued mail |
| Security alerts | Emails on a sign-in from a new device (OWASP-style device cookie), password, email address and two-factor changes and linked accounts |
| Broadcasts | Owner-only announcements by role in an optional category, with preview, password check, background queuing and sending after all other mail |
| API | Versioned REST API, RFC 9457 problem details with stable `urn:problem` types, pagination with an allow-listed sort, OpenAPI document and Swagger UI, generated TypeScript (or any language) client |
| Protection | Deny-by-default security, CSRF guard for cookie clients, CORS, security headers and CSP, per-IP and per-route rate limiting, audit log in an append-only table |
| Data | PostgreSQL with Flyway migrations, optimistic locking, Redis caching that fails open, statement and idle-transaction timeouts |
| Operations | `prod` profile that refuses development defaults, JSON logs, Prometheus metrics with alert rules and a Grafana dashboard, OpenTelemetry tracing, health probes, runbooks |
| Delivery | Multi-arch Docker image signed with cosign, SBOM and provenance, Kubernetes manifests for the `restricted` Pod Security Standard, a release script |
| Quality | Unit and Testcontainers integration tests, JaCoCo and PIT gates, ArchUnit rules, Error Prone, SpotBugs with FindSecBugs, OpenAPI contract tests, a k6 load test, CodeQL and OpenSSF Scorecard |

## How it fits together

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/public/diagrams/architecture-dark.svg" width="720" height="568">
  <img src="docs/public/diagrams/architecture-light.svg" width="720" height="568" alt="Clients call the Spring Boot app over HTTPS through its security filters (trace id, rate limit, CSRF, JWT); REST controllers hand requests to the services, which use PostgreSQL and Redis and reach an SMTP server and GitHub or Google; Prometheus scrapes the app.">
</picture>

The [guides](https://hikmethankolay.github.io/spring-boot-backend-template/) have a diagram for every flow: request
checks, login with two-factor authentication and refresh, sign-up, GitHub and Google login, ending sessions, the email
outbox, the data model, Kubernetes and the release pipeline. The
[ADRs](https://hikmethankolay.github.io/spring-boot-backend-template/adr/0001-jwt-signing/) record why each major
choice was made.

## Quick start

You need **JDK 25** (a JRE is not enough) and **Docker or Podman**, or nothing at all: open the repository in
**GitHub Codespaces** (*Code → Codespaces → Create codespace*) or in VS Code's *Dev Containers*, which run `setup`
for you, then continue with `scripts/dev.sh`. With only Docker or Podman, run `setup`, then `scripts/docker.sh up`
instead of `dev`: the image is built in a container.

```bash
git clone https://github.com/hikmethankolay/spring-boot-backend-template.git
cd spring-boot-backend-template
scripts/setup.sh    # checks prerequisites, creates .env with generated secrets, compiles
scripts/dev.sh      # starts PostgreSQL, Redis and Mailpit, then the app
scripts/smoke.sh    # in a second terminal: sign up, log in, email preferences, the notes API, log out, delete
```

On Windows, run the `.bat` versions: `scripts\setup.bat`, `scripts\dev.bat`, `scripts\smoke.bat`.

Then open:

| | |
|---|---|
| API | http://localhost:8080/api/v1 |
| Interactive API docs | http://localhost:8080/swagger-ui.html |
| Emails the app sent (sign-up links, password resets, security alerts), as HTML, text and raw | http://localhost:8025 |

`setup` prints the password of the owner account `admin` and stores it in `.env`.
To stop the app, press Ctrl+C and run `scripts/db.sh down`.

## Scripts

Each script exists as `scripts/<name>.sh` and `scripts\<name>.bat`.

| Script | What it does |
|--------|--------------|
| `setup` | Checks prerequisites, creates `.env` with generated secrets, compiles |
| `dev` | Starts PostgreSQL, Redis and Mailpit, then runs the app from source |
| `db` | Local services: `up`, `down`, `reset`, `logs`, `psql`, `redis-cli`, `status` |
| `test` | All tests and the coverage report |
| `build` / `run` | Build the jar (`--skip-tests` to skip tests) / run it with `.env` |
| `docker` | Whole stack in containers: `up`, `down`, `logs`, `ps` |
| `smoke` | End-to-end check against a running instance |
| `load-test` | k6 load test (see [Testing](https://hikmethankolay.github.io/spring-boot-backend-template/guides/testing/#load-test)) |
| `docs` / `site` | Java reference (Javadoc) / the documentation website |
| `client` | A TypeScript client for your frontend from the OpenAPI document, or with `--generator` one in another language |
| `rename-project` | Rename the Maven coordinates, app name and Java package, and point the repository links to your repository |
| `release` | Release commit and tag for a version (`scripts/release.sh 4.1.0`), pushed after asking |

## Start your own project

Create your repository with **Use this template**, clone it, then:

```bash
scripts/rename-project.sh com.acme order-service com.acme.orders
scripts/test.sh
```

Then add your tables as Flyway migrations and your features as packages next to `user/`. Delete what you don't need,
such as `account/`. The build checks the basics for you: controllers use services, entities stay out of the API,
and every public class is documented. See [Using the template](https://hikmethankolay.github.io/spring-boot-backend-template/guides/new-project/).

## Documentation

| Guide | Covers |
|-------|--------|
| [Configuration](https://hikmethankolay.github.io/spring-boot-backend-template/guides/configuration/) | Every environment variable and its default |
| [Security](https://hikmethankolay.github.io/spring-boot-backend-template/guides/security/) | Tokens, revocation, account flows, lockout, rate limiting, security alerts, unsubscribe links |
| [Email templates](https://hikmethankolay.github.io/spring-boot-backend-template/guides/email-templates/) | The emails the app sends, adding one, categories and unsubscribe, security alerts, broadcasts |
| [Connecting a frontend](https://hikmethankolay.github.io/spring-boot-backend-template/guides/frontend/) | The pages to build, cookies or Bearer tokens, the two-factor login step, refreshing, errors |
| [API overview](https://hikmethankolay.github.io/spring-boot-backend-template/guides/api/) and [REST API reference](https://hikmethankolay.github.io/spring-boot-backend-template/api/) | Endpoints, errors (RFC 9457), pagination, headers |
| [Caching](https://hikmethankolay.github.io/spring-boot-backend-template/guides/caching/) and [Observability](https://hikmethankolay.github.io/spring-boot-backend-template/guides/observability/) | Redis cache, metrics, alerts, dashboard, tracing, logs, email outbox |
| [Production](https://hikmethankolay.github.io/spring-boot-backend-template/guides/production/) | The `prod` profile, Docker and Kubernetes, image verification, the go-live checklist and trade-offs |
| [Java reference](https://hikmethankolay.github.io/spring-boot-backend-template/reference/) and [coverage](https://hikmethankolay.github.io/spring-boot-backend-template/coverage/) | Generated from the code on every release |

The guides live in [`docs/`](docs/src/content/docs) and are published with every `v*` release.

## Contributing

See [CONTRIBUTING.md](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/CONTRIBUTING.md). Report vulnerabilities privately as described in [SECURITY.md](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/SECURITY.md).

## License

[MIT](LICENSE) © 2025-2026 Hikmethan Kolay

## Author

- **Hikmethan Kolay** - [GitHub](https://github.com/hikmethankolay)
- **Email**: hikmethan.kolay@gmail.com
