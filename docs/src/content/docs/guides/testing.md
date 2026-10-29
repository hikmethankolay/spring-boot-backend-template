---
title: Testing
description: Unit, integration, architecture and load tests.
---

```bash
scripts/test.sh            # unit, integration and ArchUnit tests, coverage, SpotBugs and formatting checks
scripts/test.sh --unit     # unit tests only: no Spring context, no containers, a few seconds
scripts/docs.sh            # fails on any Javadoc warning, including undocumented code
scripts/load-test.sh       # k6: realistic user journeys; fails on slow requests, errors or 429s
```

## Unit and integration tests

Tests named `*Test` are unit tests: Surefire runs them in `./mvnw test`, without a Spring context or containers.
Tests named `*IntegrationTest` run in `./mvnw verify` through Failsafe. Run one class of either kind with
`scripts/test.sh --unit -Dtest=UserServiceTest` or `scripts/test.sh -Dit.test=UserIntegrationTest`.

Integration tests extend `IntegrationTest`, which starts the full application against one throwaway
`postgres:18-alpine` and one `redis:8-alpine` container (Testcontainers), shared by all test classes. Outgoing mail goes through
the email outbox and is captured in memory (`TestMailConfiguration`), so tests can follow the verification and reset
links from the emails. `CapturingMailSender.latest(to, subject)` returns the text part of HTML emails too, and
`html(message)` and `mime(message)` return the HTML part and the MIME message for headers, e.g.
`List-Unsubscribe` on optional emails (`EmailPreferenceIntegrationTest`). Security alerts are off in the test
profile, so other tests get no extra emails; `SecurityAlertIntegrationTest` turns them on with
`@TestPropertySource(properties = "app.mail.security-alerts.enabled=true")`.
`CapturingMailSender.failNext(n)` simulates an SMTP outage, and `EmailOutbox.sendDue(time)`
runs a retry pass at a chosen time. The test profile sets the background retry to once an hour
(`application-test.yml`), so it does not interfere with a test.
Without a container runtime the integration tests fail instead of being skipped, so a passing build always means every
test ran. Use `scripts/test.sh --unit` to run only the unit tests.

```java
class OrderIntegrationTest extends IntegrationTest {

    @Test
    void createsOrder() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        mockMvc.perform(post("/api/v1/orders").with(bearer(token)) ...)
               .andExpect(status().isCreated());
    }
}
```

## API contract tests

`OpenApiContractIntegrationTest` reads `/v3/api-docs` and checks that the document matches the API's behaviour: status codes,
problem-detail errors, public and protected operations, pagination parameters, response headers, operationIds and
required fields. `OpenApiSpecExportIntegrationTest` compares the document with the committed `api/openapi.json` and fails on any
difference; regenerate the file with `-Dopenapi.update=true`, see [API overview](../api/#openapi-document).

## Quality gates

`./mvnw verify` (and so `scripts/test.sh` and CI) fails when:

- **Coverage** drops below the JaCoCo minimums in `pom.xml`: lines and branches overall, and lines in the `security` and
  `auth` packages. Filtered runs (`-Dtest`, `-Dit.test`) skip the check.
- **Formatting** differs from `palantir-java-format` (Spotless). `scripts/test.sh` formats first; otherwise run
  `./mvnw spotless:apply`.
- **SpotBugs** with FindSecBugs (maximum effort, low threshold) reports a bug. Accepted findings go in
  `spotbugs-exclude.xml` with a reason.
- **Error Prone** finds an error-level bug pattern while compiling.
- The **Maven Enforcer** finds a JDK older than 25, Maven older than 3.9, or conflicting dependency versions.

**Mutation testing** (PIT) changes the code in the `security` and `auth` packages and checks that a test fails each
time. It runs weekly in CI (`.github/workflows/mutation.yml`) and fails below the threshold in `pom.xml`; run it with
`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage` (report in `target/pit-reports`).

## Architecture tests

`ArchitectureTest` enforces the layering rules on every build. See [Using the template](../new-project/#rules-the-build-enforces).

## Load test

`load-tests/api.js` runs the journeys real clients take, at the same time:

| Journey | Users | What it does |
|---|---|---|
| `browse` | `VUS` | Returning users log in (by username or email), read their profile, sometimes export their data, refresh the token and usually log out |
| `signup` | `VUS / 10` | New users register, log in, edit their profile, change their password, log in again and delete their account |
| `admin` | 1 | The owner pages through the user list with random sizes and sort orders and opens accounts (needs `ADMIN_USERNAME` and `ADMIN_PASSWORD`, read from `.env`) |
| `anonymous` | `VUS / 10` | Password reset requests, calls without a token and, with `FAILED_LOGINS=true`, wrong passwords |

Sign-up is email-first, so the test reads the sign-up links from Mailpit
(`MAILPIT_URL`, default `http://localhost:8025`; `scripts/smoke.sh` does the same). All requests come from one IP,
so start the target with `RATE_LIMIT_ENABLED=false`. Also raise the login lockout with `FAILED_LOGINS=true`, or for a
profile with many users (`stress`, `spike`): the lockout counts every login still in progress, so more than
`LOGIN_MAX_ATTEMPTS` (10) concurrent logins from that one IP are answered `429`:

```bash
RATE_LIMIT_ENABLED=false LOGIN_MAX_ATTEMPTS=1000000 LOGIN_LOCK_DURATION=1m scripts/dev.sh   # terminal 1
PROFILE=smoke scripts/load-test.sh                                                           # terminal 2
```

`PROFILE` picks the load shape: `smoke` (30 s, one user per journey), `load` (default: 1 min ramp, 3 min at the
peak), `stress` (the peak, then twice and three times it), `spike` (a sudden jump to five times the peak) and `soak`
(1 h at the peak). `DURATION` changes the time at the peak and `THINK_TIME` (default `1` s, randomised) the pause
between a user's requests. The test users are created in setup and deleted at the end.

A user whose journey fails waits about five think times before starting again, as a real user would, so an
overloaded app does not also get a retry storm. Requests carry `X-Requested-With: XMLHttpRequest`, as a browser app's
do: k6 sends back the auth cookies that login sets, and the [CSRF guard](../security/) rejects a cookie request
without that header.

Each kind of request has its own threshold, so one slow kind cannot hide behind fast ones: p95 under 300 ms for
reads, 500 ms for writes and 1.5 s for requests that check a password with Argon2id, with fewer than 1% failed
requests or journeys and no 429.

`PROFILE=throughput` measures raw capacity instead: every user reads `/api/v1/users/me` without pausing.

```bash
PROFILE=throughput VUS=100 DURATION=20s scripts/load-test.sh
```

### Results on 2 CPUs and 4 GB

These numbers come from a small server: the app, PostgreSQL and Redis sharing 2 CPUs and 4 GB (one Podman pod
started with `--cpus 2 --memory 4g`, with k6 and Mailpit outside it), version 4.1. Run the test once before you
measure: a freshly started JVM is still compiling its hot code and served less than half as much.

How to read them: *req/s* is how many requests the app answered per second. *p95* is the response time that 95 of
100 requests beat, a near-worst case. A *user* is one k6 user going through a journey with about a second's pause
between clicks, so 200 users means 200 people clicking at the same moment, not 200 accounts.

| Test | Result | What it means |
|---|---|---|
| `throughput`: the cheapest request, no pauses | **About 9,100 req/s** at its peak, with 100 users (p95 40 ms) | The ceiling. Both CPUs were full from 10 users on: more users made everyone wait longer (p95 161 ms at 800) and lowered throughput by about 15%, but nothing failed. |
| `stress`: real journeys, 200 users | **About 320 req/s**, every p95 within its threshold, 1.65 of 2 CPUs busy | Comfortable: fast, with room left for bursts. |
| `stress`: real journeys, 300 users | **About 475 req/s**, both CPUs full, password requests at a p95 of 0.8 s | The limit: still within the thresholds, with nothing to spare. |

Real traffic gets about 20 times less than the ceiling because of password checks. Argon2id is slow on purpose, so
that a leaked password hash is expensive to crack: one check costs about 35 ms of CPU, about 150 times a profile
read. Two CPUs therefore handle about **55 logins, sign-ups or password changes per second**, and the share of those
in your traffic decides your capacity more than anything else. Memory was never the limit: the whole pod peaked at
1.7 GB of 4 GB. Tracing every request (`TRACING_SAMPLING_PROBABILITY=1.0`) made no measurable difference while no
exporter was configured.

Past 300 users the run broke down, mostly because of how it was run rather than the app:

- **429 on login:** the lockout counts every login still in progress, and all k6 users share one IP, so more than
  10 slow logins at once blocked it. Real users come from many addresses; for a run like this, raise
  `LOGIN_MAX_ATTEMPTS` as described above.
- **500 with `Connection is not available`:** the blocked users retried at once (the script now pauses after a
  failure), and even a blocked login reads the user and writes an audit entry, so the 10 database connections ran
  out. With virtual threads, the connection pool is the only limit on concurrent work. In production the rate limit
  stops such a flood before it reaches the database, so keep it on.

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main`, on pull requests and by hand. Its eight jobs run in parallel and
each fails on its own:

- **build:** `./mvnw verify`, the tests and every quality gate above, then uploads the coverage report.
- **windows:** `scripts\test.bat --unit`, `scripts\docs.bat` and `scripts\build.bat --skip-tests`, so the `.bat`
  scripts cannot drift from the `.sh` ones.
- **image:** builds the Docker image and scans it with Trivy, failing on fixable critical or high vulnerabilities.
- **secrets:** scans the pushed or pull request commits for secrets with gitleaks. The test fixtures that look like
  secrets
  are allowlisted by exact value in `.gitleaks.toml` (so this also works in a repository created from the template);
  `.gitleaksignore` lists two findings from this repository's own history (`rename-project.sh` deletes it). Anything
  new fails the job.
- **zap:** starts the app with `docker compose --profile app` and runs a ZAP baseline scan against it, failing on any
  alert that `.zap/rules.tsv` does not ignore with a reason. The scan is passive and reaches what is public without
  a token (security headers, error responses); Swagger UI and the OpenAPI document are out of its scope.
- **docs:** `scripts/docs.sh`, which fails on undocumented code, then uploads the Java reference.
- **rename:** only in the template repository. Runs `rename-project.sh` the way a new project does, fails if a
  template reference is left, and runs the unit tests.
- **clients:** generates the TypeScript client with `scripts/client.sh` and type-checks it.

Every action is pinned to a commit SHA and every base image, the ZAP image included, to a digest, so a moved tag
cannot change the build. Dependabot opens weekly update PRs for Maven, GitHub Actions (SHA pins included), Docker
digests, the `compose.yaml` images and npm.
