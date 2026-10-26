---
title: Getting started
description: Run the template locally in three commands.
---

## Prerequisites

- **JDK 25.** A JRE is not enough, because Maven needs `javac`. For example `sudo dnf install java-25-openjdk-devel`,
  `sudo apt install openjdk-25-jdk`, or `winget install EclipseAdoptium.Temurin.25.JDK` on Windows.
- **Docker or Podman**, for PostgreSQL, Redis and Mailpit in development and for the integration tests.

Only have Docker or Podman? `setup` notices the missing JDK, skips compiling, and you can
[run everything in containers](#run-everything-in-containers) right away. Install the JDK when you start changing code.

## Run it

```bash
git clone https://github.com/hikmethankolay/spring-boot-backend-template.git
cd spring-boot-backend-template

scripts/setup.sh    # checks prerequisites, creates .env with generated secrets, compiles
scripts/dev.sh      # starts PostgreSQL, Redis and Mailpit, then the app on http://localhost:8080
scripts/smoke.sh    # in another terminal: register, log in, use the notes API, refresh, log out, delete the account
```

On Windows use the same names with `.bat`: `scripts\setup.bat`, `scripts\dev.bat`, `scripts\smoke.bat`.

## Where to look

| What | URL |
|------|-----|
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Emails sent in development | http://localhost:8025 (Mailpit) |
| Health | http://localhost:8080/actuator/health |

`setup` prints the generated password of the owner account `admin`, which is created on first start and stored
in `.env`.

## Try the API

```bash
curl -i -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com"}'
```

Sign-up is email-first. Open Mailpit at <http://localhost:8025>, copy the `token` from the link in the
"Finish creating your account" email, choose the username and password, then log in:

```bash
curl -i -X POST localhost:8080/api/v1/auth/register/complete -H 'Content-Type: application/json' \
  -d '{"token":"<token from the link>","username":"alice","password":"lantern-orbit-maple-42"}'

TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"identifier":"alice","password":"lantern-orbit-maple-42"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

curl -s localhost:8080/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```

Until the link is opened the account does not exist, so login fails like for an unknown user. This is what keeps
registration from revealing which addresses already have an account (see [Security](../security/#account-flows)).

The login response also contains a `refreshToken`. When the access token expires after 15 minutes, exchange it for a
new pair:

```bash
curl -s -X POST localhost:8080/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refreshToken from the login response>"}'
```

## Run everything in containers

To run the app from its Docker image instead of from source, together with PostgreSQL, Redis and Mailpit (no JDK
needed; the image is built in a container):

```bash
scripts/setup.sh         # once: creates .env with generated secrets
scripts/docker.sh up     # builds the image and waits until every container is healthy
scripts/smoke.sh         # same check as above
scripts/docker.sh down
```

## Stop it

Press **Ctrl+C** to stop the app, then `scripts/db.sh down` to stop the containers. Data is kept until you run
`scripts/db.sh reset`.

To see what is running, use `docker ps` or `podman ps`.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `bind: address already in use` for port 8080 | Another process (often an earlier `scripts/dev.sh`) holds the port. Find it with `ss -ltnp \| grep 8080` (Linux) or `lsof -i :8080` (macOS) and stop it, or start on another port: `PORT=8090 scripts/docker.sh up`. |
| `permission denied … /var/run/docker.sock` | You ran a `docker` command, but your runtime is rootless Podman. Use `podman …` / `podman compose …`, or the scripts, which pick the right one. |
| Integration tests fail with `Could not find a valid Docker environment` | No container runtime was found. Start Docker, or let `scripts/test.sh` start `podman.socket`; `scripts/test.sh --unit` runs the unit tests without one. |
| Login returns `401` for `admin` | The password is the one `setup` wrote to `.env` (`ADMIN_PASSWORD`), unless it was changed later through the API. Reset it with the password reset flow; the email arrives in Mailpit. |
