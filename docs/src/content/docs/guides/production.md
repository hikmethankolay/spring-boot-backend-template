---
title: Production
description: The prod profile, deployment and the deliberate trade-offs.
---

## The prod profile

Start the app with `SPRING_PROFILES_ACTIVE=prod` to switch to:

- ECS JSON logs
- `Secure` cookies
- required email verification
- Swagger UI and API docs disabled
- 10% trace sampling
- Actuator on port `8081`, which must stay internal
- a startup check that refuses the development defaults: the app does not start while `CORS_ALLOWED_ORIGINS`,
  `MAIL_LINK_BASE_URL` or (with GitHub or Google login on) `OAUTH2_REDIRECT_URI` point to localhost, while
  `DB_PASSWORD` is the local `app`, while `MAIL_HOST`/`MAIL_PORT` are the local Mailpit (`localhost:1025`), or while
  `MAIL_FROM` is an `example.com`, `example.net` or `example.org` address. Without the mail checks, verification
  emails would never arrive and nobody could finish signing up. The error names every variable to set.

Each setting can still be overridden with its environment variable.

## Deploying

The Docker image (multi-stage, layered jar, JRE on Alpine) runs as the non-root user `10001`, has a `HEALTHCHECK`,
and exposes `8080` for the API and `8081` for Actuator. Pushing a `v*` tag runs the release workflow:

1. It runs the tests.
2. It publishes the image to `ghcr.io/<owner>/<repo>` for `linux/amd64` and `linux/arm64`, with an SBOM and build
   provenance attached.
3. It signs the image with [Sigstore cosign](https://docs.sigstore.dev/) (keyless, through GitHub's OIDC identity),
   then runs the verification below against what it just published.
4. It creates a GitHub release with the SBOM (`sbom.spdx.json`), its signature and its SLSA provenance attached, and
   the documentation site is rebuilt.

```bash
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... \
  -e REDIS_HOST=... -e REDIS_PASSWORD=... -e JWT_SECRET=... -e MFA_ENCRYPTION_KEY=... -e MAIL_HOST=... \
  -e CORS_ALLOWED_ORIGINS=https://app.example.com -e MAIL_LINK_BASE_URL=https://app.example.com \
  ghcr.io/hikmethankolay/spring-boot-backend-template:latest
```

`podman run` takes the same arguments.

### Verify the image

Check that an image was built by this repository's release workflow before deploying it:

```bash
cosign verify ghcr.io/hikmethankolay/spring-boot-backend-template:3.2.9 \
  --certificate-identity-regexp '^https://github.com/hikmethankolay/spring-boot-backend-template/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# Build provenance (SLSA) and the SBOM (SPDX) attached to the image; needs Docker with buildx
docker buildx imagetools inspect ghcr.io/hikmethankolay/spring-boot-backend-template:3.2.9 \
  --format '{{ json (index .Provenance "linux/amd64").SLSA }}'
docker buildx imagetools inspect ghcr.io/hikmethankolay/spring-boot-backend-template:3.2.9 \
  --format '{{ json (index .SBOM "linux/amd64").SPDX }}'
```

From 2.9.1 on, the attached SBOM comes with a Sigstore signature bundle and SLSA level 3 provenance.
Verify both before trusting the file
([slsa-verifier](https://github.com/slsa-framework/slsa-verifier) checks the provenance):

```bash
tag=v3.2.9
gh release download "$tag" -R hikmethankolay/spring-boot-backend-template -p 'sbom.spdx.json*'
cosign verify-blob sbom.spdx.json --bundle sbom.spdx.json.sigstore.json \
  --certificate-identity-regexp '^https://github.com/hikmethankolay/spring-boot-backend-template/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
slsa-verifier verify-artifact sbom.spdx.json --provenance-path sbom.spdx.json.intoto.jsonl \
  --source-uri github.com/hikmethankolay/spring-boot-backend-template --source-tag "$tag"
```

`cosign` and `docker buildx` work against the registry, so the image does not have to be pulled first. Both
architectures (`linux/amd64`, `linux/arm64`) share one tag; `docker pull` and `podman pull` pick the right one.

### Kubernetes

[`deploy/kubernetes`](https://github.com/hikmethankolay/spring-boot-backend-template/tree/main/deploy/kubernetes) is a
Kustomize base:

- **Deployment:** two replicas, rolling updates without downtime, and startup, liveness and readiness probes on the
  management port. A `preStop` pause plus graceful shutdown keeps in-flight requests from failing.
- **Pod security:** passes the `restricted` Pod Security Standard (non-root, read-only root filesystem, no
  capabilities, `RuntimeDefault` seccomp).
- **Service:** for the API port only.
- **Scaling and disruptions:** a HorizontalPodAutoscaler on CPU and a PodDisruptionBudget.
- **NetworkPolicy:** the API port is reachable only from the ingress controller's namespace (`ingress-nginx`; adjust it) and the management port only from the `monitoring` namespace.

```bash
kubectl -n backend create secret generic app-secrets --from-literal=JWT_SECRET=... # see deploy/kubernetes/README.md
kubectl apply -k deploy/kubernetes
```

PostgreSQL, Redis and SMTP are expected to be managed services or deployed separately.

### Database connections

Each instance opens up to `DB_POOL_SIZE` connections (10). Keep *maximum replicas × `DB_POOL_SIZE`* below the
server's `max_connections`, or put PgBouncer in front of it. Every connection has `statement_timeout` (30 s) and
`idle_in_transaction_session_timeout` (60 s), so a runaway query or a forgotten transaction cannot exhaust the pool.

### Mail encryption

The prod profile sends mail only over TLS, so neither the emails (with their reset links) nor the SMTP password cross
the network in clear. SMTP starts TLS in one of two ways, and both work:

| Port | How TLS starts | Settings |
|------|----------------|----------|
| 587 (used in `deploy/kubernetes`) | STARTTLS: the connection starts in plain text and is upgraded before the login | `MAIL_PORT=587` |
| 465 | Implicit TLS: encrypted from the first byte | `MAIL_PORT=465` and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true` |

Prefer 465 when your provider offers it: it has no plain-text phase, which is what RFC 8314 recommends. STARTTLS on
587 is still safe here because the prod profile requires it (`MAIL_STARTTLS_REQUIRED=true`): if the server, or an
attacker on the path, drops the upgrade, the email fails and is retried instead of going out unencrypted. On 465 the
STARTTLS step is skipped, because the connection is already encrypted, so the setting can stay on.

Set `MAIL_STARTTLS_REQUIRED=false` only for a relay without TLS on the same host or private network. The other
profiles use STARTTLS when the server offers it, so the local Mailpit, which has no TLS, keeps working.

## Checklist

- [ ] Set real values for `CORS_ALLOWED_ORIGINS` and `MAIL_LINK_BASE_URL`.
- [ ] Use an SMTP server (`MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`) over TLS on 465 or 587 (see
      [Mail encryption](#mail-encryption)) and a sender of your own domain (`MAIL_FROM`).
- [ ] Use a Redis with a password and TLS (`REDIS_PASSWORD`, `REDIS_SSL_ENABLED=true`).
- [ ] Behind a proxy, set `FORWARD_HEADERS_STRATEGY=native` and allow only the proxy to reach the API port.
- [ ] Keep port `8081` internal and scrape metrics there.
- [ ] Load `ops/prometheus/alerts.yml` and route the alerts to someone.
- [ ] Ship the `audit` logger to long-term storage.
- [ ] Generate a long random `JWT_SECRET` and store it in your secret manager.
- [ ] Generate a separate random `MFA_ENCRYPTION_KEY`, store it in your secret manager (not with the database
      backups) and back it up: losing it breaks every user's two-factor authentication.
- [ ] Back up PostgreSQL (point-in-time recovery) and test a restore.
- [ ] Deploy only signed images (`cosign verify`, or an admission policy such as Kyverno or Sigstore policy-controller).

## Trade-offs to revisit

- **Fail-open on Redis outages.** The cache, rate limit and access-token revocation keep the API available when
  Redis is down, at the cost of briefly disabling those protections; new logins fail with `503` until it is back.
  Refresh tokens live in PostgreSQL and are unaffected. Run Redis with high availability, or change the handlers to
  fail closed.
- **Redis memory.** Redis holds security state, so it runs with `noeviction`. Size it for your traffic; every key has
  a TTL.
- **Access tokens after logout of other sessions.** Revoking a session deletes its refresh token immediately; its
  access token is rejected through Redis. If Redis was unavailable at that moment, the access token stays valid until
  it expires, at most `JWT_ACCESS_TOKEN_TTL`. Keep that value short.
- **Concurrent refreshes.** Two simultaneous refreshes with the same token count as reuse and end the session. Clients
  must serialise refreshes (see [Security](../security/#rotation-and-reuse-detection)).
- **Single sign-on.** For SSO or many services, consider an external identity provider and keep only the
  resource-server part.
- **Dependency overrides.** `pom.xml` pins a few patched dependency versions. Remove each override once a Spring Boot
  upgrade manages an equal or newer version.
