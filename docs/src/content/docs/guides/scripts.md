---
title: Scripts
description: One script per task, for Linux, macOS (.sh) and Windows (.bat).
---

Every script exists as `scripts/<name>.sh` and `scripts\<name>.bat`.

| Script | What it does |
|--------|--------------|
| `setup` | Checks JDK 25 and a container runtime, creates `.env` from `.env.example` with a random `JWT_SECRET`, `MFA_ENCRYPTION_KEY` and admin password, compiles. Without a JDK but with a container runtime, it skips compiling and points to `docker up`. `--force` recreates `.env`. |
| `dev` | Starts PostgreSQL, Redis and Mailpit, then runs the app from source with `spring-boot:run`. |
| `db` | Local services: `up`, `down`, `reset` (deletes data), `logs`, `psql`, `redis-cli`, `status`. |
| `test` | Formats the code, runs unit, integration and architecture tests, and checks coverage minimums, formatting and SpotBugs (report: `target/site/jacoco/index.html`). `--unit` runs only the unit tests. Extra arguments go to Maven, e.g. `--unit -Dtest=UserServiceTest` or `-Dit.test=UserIntegrationTest`. |
| `build` | Builds `target/*.jar`; runs the tests unless `--skip-tests`. |
| `run` | Runs the built jar with the settings from `.env`. |
| `docker` | Whole stack in containers (PostgreSQL, Redis, Mailpit, app image): `up` (builds the image), `down`, `logs`, `ps`. The app is on `PORT` (default 8080). |
| `smoke` | End-to-end check against a running instance: `scripts/smoke.sh [base-url]`. |
| `load-test` | k6 load test against a running instance. See [Testing](../testing/#load-test). |
| `docs` | Javadoc HTML in `target/reports/apidocs`. Fails on any Javadoc warning, including undocumented code. |
| `site` | Builds this documentation website into `target/pages`, running the tests and Javadoc first; `--skip-build` reuses their existing output. |
| `client` | An API client from `api/openapi.json`, generated with OpenAPI Generator in a container: TypeScript (`typescript-fetch`) by default, another language with `--generator <name>`. `scripts/client.sh [--generator <name>] [output-dir]` (default `target/client/<name>`). See [API overview](../api/#typescript-client). |
| `rename-project` | Renames Maven coordinates, app name and Java package, and points the repository links to your GitHub repository (`origin`, or `--repo owner/name`). See [Using the template](../new-project/). |
| `release` | `scripts/release.sh <version>` on an up-to-date `main`: moves the `[Unreleased]` notes of `CHANGELOG.md` under the version, sets the image tag in `deploy/kubernetes` and the version in the guides' examples, commits, tags, and pushes after asking. The release workflow then publishes the image and pins its digest. See [Production](../production/#releasing-to-kubernetes). |

## Container runtimes

- **Linux and macOS:** every script uses Docker when its daemon is reachable and rootless Podman otherwise
  (`podman compose` for the containers). `test` also starts `podman.socket` and points Testcontainers at it.
- **Running compose yourself:** with Podman, type `podman compose …` wherever the docs say `docker compose …`. A plain
  `docker` command fails with *permission denied … /var/run/docker.sock* because it looks for a rootful Docker daemon.
- **Windows:** the `.bat` scripts need Docker Desktop, or Podman with its Docker API enabled. `rename-project.bat` and
  `release.bat` run the shell script through Git Bash.
