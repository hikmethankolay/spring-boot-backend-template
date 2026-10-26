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
links from the emails. `CapturingMailSender.failNext(n)` simulates an SMTP outage, and `EmailOutbox.sendDue(time)`
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

All requests come from one IP, so start the target with `RATE_LIMIT_ENABLED=false`. With `FAILED_LOGINS=true`, also
raise the login lockout, or the run locks the IP out:

```bash
RATE_LIMIT_ENABLED=false LOGIN_MAX_ATTEMPTS=1000000 LOGIN_LOCK_DURATION=1m scripts/dev.sh   # terminal 1
PROFILE=smoke scripts/load-test.sh                                                           # terminal 2
```

`PROFILE` picks the load shape: `smoke` (30 s, one user per journey), `load` (default: 1 min ramp, 3 min at the
peak), `stress` (the peak, then twice and three times it), `spike` (a sudden jump to five times the peak) and `soak`
(1 h at the peak). `DURATION` changes the time at the peak and `THINK_TIME` (default `1` s, randomised) the pause
between a user's requests. The test users are created in setup and deleted at the end.

Each kind of request has its own threshold, so one slow kind cannot hide behind fast ones: p95 under 300 ms for
reads, 500 ms for writes and 1.5 s for requests that check a password with Argon2id, with fewer than 1% failed
requests or journeys and no 429. With the default `load` profile (25 users), one instance on a developer machine
answered reads at a p95 of 4 ms, writes at 16 ms and password requests at 136 ms.

`PROFILE=throughput` measures raw capacity instead: every user reads `/api/v1/users/me` without pausing.

```bash
PROFILE=throughput VUS=100 DURATION=20s scripts/load-test.sh
```

On a 20-core developer machine, one instance served about **7,500 req/s at a p95 of 19 ms**, with k6 running on the
same machine.

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests:

1. It runs the tests.
2. It builds the Docker image and scans it with Trivy, failing on fixable critical or high vulnerabilities.
3. It checks the documentation.

Every action is pinned to a commit SHA and both base images to a digest, so a moved tag cannot change the build.
Dependabot opens weekly update PRs for Maven, GitHub Actions (SHA pins included), Docker digests and npm.
