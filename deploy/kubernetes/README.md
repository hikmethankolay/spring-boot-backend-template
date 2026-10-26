# Kubernetes manifests

A hardened starting point for running the image on Kubernetes with Kustomize: two or more
replicas, probes on the management port, a read-only root filesystem, a non-root user, secrets
mounted as files, an autoscaler, a disruption budget, a network policy for incoming and outgoing
traffic, migrations run as a separate Job, and the image pinned by digest. PostgreSQL, Redis and
SMTP are expected to exist already (managed services or their own charts). Needs Kubernetes 1.30
or newer (the `preStop` sleep action).

1. Edit `configmap.yaml` (hosts, URLs, origins, `MAIL_FROM`), and the image in `kustomization.yaml` and
   `migrate-job.yaml` (tag and digest). The `prod` profile refuses to start with an `example.com` sender. Create the
   two database roles described in the Production guide (*Database roles*): `app_owner` owns the schema, `app` may
   only read and write rows.
2. Create the secrets (never commit them):

   ```bash
   kubectl create namespace backend
   kubectl -n backend create secret generic app-secrets \
     --from-literal=DB_USERNAME=app --from-literal=DB_PASSWORD='...' \
     --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
     --from-literal=MFA_ENCRYPTION_KEY="$(openssl rand -base64 48)" \
     --from-literal=REDIS_PASSWORD='...' \
     --from-literal=SPRING_MAIL_USERNAME='...' --from-literal=SPRING_MAIL_PASSWORD='...' \
     --from-literal=ADMIN_USERNAME=admin --from-literal=ADMIN_EMAIL='you@example.com' \
     --from-literal=ADMIN_PASSWORD="Admin#$(openssl rand -hex 12)"
   ```

   The three `ADMIN_*` values create the owner account on the first start; without them nobody can manage users
   or roles. The password needs at least 15 characters and must not be a common or breached password. Read it back with
   `kubectl -n backend get secret app-secrets -o jsonpath='{.data.ADMIN_PASSWORD}' | base64 -d`, change it after the
   first login, then remove the three keys from the secret.

   The keys become files in `/etc/app/secrets` (read through `spring.config.import`), not environment variables.
   The migration Job gets only the schema owner's credentials:

   ```bash
   kubectl -n backend create secret generic app-migrator-secrets \
     --from-literal=DB_USERNAME=app_owner --from-literal=DB_PASSWORD='...'
   ```

3. Migrate, then apply. Do both for every release: the pods run with `FLYWAY_ENABLED=false`.

   ```bash
   kubectl -n backend delete job migrate --ignore-not-found
   kubectl apply -f deploy/kubernetes/migrate-job.yaml
   kubectl -n backend wait --for=condition=complete job/migrate --timeout=10m
   kubectl apply -k deploy/kubernetes
   ```
4. Route an Ingress or Gateway to the `app` Service (port 80) with TLS.
5. Optional, recommended: install the [Sigstore policy-controller](https://docs.sigstore.dev/policy-controller/overview/)
   and `kubectl apply -f deploy/kubernetes/policy/`. The `backend` namespace is labelled for it, so it then admits only
   images this repository's release workflow signed.

Scrape `/actuator/prometheus` on port 8081 (the pods carry `prometheus.io/*` annotations) and load
`ops/prometheus/alerts.yml` into Prometheus. `AppInstanceDown` selects the scrape job `app`: name the job that scrapes
these pods `app`, or change `job="app"` in that rule, or the alert never fires.
