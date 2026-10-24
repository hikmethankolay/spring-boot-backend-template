# Kubernetes manifests

A hardened starting point for running the image on Kubernetes with Kustomize: two or more
replicas, probes on the management port, a read-only root filesystem, a non-root user, an
autoscaler, a disruption budget and a network policy. PostgreSQL, Redis and SMTP are expected to
exist already (managed services or their own charts).

1. Edit `configmap.yaml` (hosts, URLs, origins, `MAIL_FROM`) and the image tag in `kustomization.yaml`. The `prod`
   profile refuses to start with an `example.com` sender.
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
   or roles. The password must have upper and lower case letters, a digit and a symbol. Read it back with
   `kubectl -n backend get secret app-secrets -o jsonpath='{.data.ADMIN_PASSWORD}' | base64 -d`, change it after the
   first login, then remove the three keys from the secret.

3. Apply: `kubectl apply -k deploy/kubernetes`
4. Route an Ingress or Gateway to the `app` Service (port 80) with TLS.

Scrape `/actuator/prometheus` on port 8081 (the pods carry `prometheus.io/*` annotations) and load
`ops/prometheus/alerts.yml` into Prometheus. `AppInstanceDown` selects the scrape job `app`: name the job that scrapes
these pods `app`, or change `job="app"` in that rule, or the alert never fires.
