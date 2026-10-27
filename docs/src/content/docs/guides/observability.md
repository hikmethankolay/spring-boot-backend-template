---
title: Observability
description: Metrics, alerts, dashboards, tracing, logs, the audit log and the email outbox.
---

## Metrics

Prometheus metrics are served at `/actuator/prometheus`, tagged with `application`. In the prod profile Actuator runs
on the internal port `8081`. Point Prometheus at that port and keep it off the public network.
Metrics are not pushed over OTLP unless `OTLP_METRICS_EXPORT_ENABLED=true`; set
`MANAGEMENT_OTLP_METRICS_EXPORT_URL` to the collector's `/v1/metrics` endpoint along with it.

Besides the standard JVM, HTTP (with latency histograms), HikariCP and cache metrics, the application publishes:

| Metric | Meaning |
|--------|---------|
| `security_audit_events_total{event}` | Security events, see [Audit log](../security/#audit-log) |
| `mail_outbox_pending` | Emails waiting to be sent, broadcasts not included |
| `mail_outbox_broadcast_pending` | Broadcast emails waiting; they drain at up to 50 per `MAIL_OUTBOX_POLL_INTERVAL` per instance |
| `mail_security_alerts_total{alert}` | Security alert emails queued, by alert (`new-login`, `password-changed`, …) |
| `mail_broadcast_recipients_total` | Broadcast emails queued |
| `mail_outbox_sent_total`, `mail_outbox_failures_total`, `mail_outbox_dropped_total` | Email delivery |
| `mail_outbox_suppressed_total{category}` | Optional emails not sent because the recipient turned their category off |
| `mail_unsubscribe_total{category,source}` | Opt-outs, by `source`: `one-click` (mail client), `link` (unsubscribe page) or `user` (preferences page) |

## Alerts and dashboard

[`ops/prometheus/alerts.yml`](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/ops/prometheus/alerts.yml)
holds alerting rules for the things that need a human:

| Group | Alerts |
|-------|--------|
| SLO | error and latency budgets burning too fast ([objectives](../runbooks/#service-level-objectives)) |
| Availability | instance down, more than 5% 5xx responses, p95 latency above 1 s |
| Security | more than 30 failed logins per minute, a reused refresh token, a role change |
| Logs | any `ERROR` line, more than 10 warnings per minute (usually Redis unreachable) |
| Email | emails given up after all retries, more than 100 emails waiting |
| Background | a `@Scheduled` job (email dispatcher, token cleanup, audit retention) throwing |
| Resources | requests waiting for database connections, heap above 90% |

Each alert has a [runbook](../runbooks/).

[`ops/grafana/dashboards/application.json`](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/ops/grafana/dashboards/application.json)
is a Grafana dashboard with traffic, latency percentiles, errors, slowest endpoints, security events, rate limiting,
the email outbox, database connections, heap and CPU.

Try both locally while the app runs (`scripts/dev.sh` or `scripts/docker.sh up`):

```bash
docker compose --profile monitoring up -d   # with Podman: podman compose --profile monitoring up -d
docker compose --profile monitoring down    # with Podman: podman compose --profile monitoring down
```

Prometheus runs on http://localhost:9090 and Grafana on http://localhost:3001; the dashboard is at
`/d/spring-boot-template`. Prometheus scrapes `host.docker.internal:8080`, the app started with `scripts/dev.sh`; for
`scripts/docker.sh up`, change the target in `ops/prometheus/prometheus.yml` to `app:8080`. `scripts/docker.sh down`
does not stop these two containers; use the
`down` command above, or `podman compose --profile app --profile monitoring down` to stop everything at once.

Grafana opens the dashboard without a login (anonymous viewer; the admin login is `admin` / `admin`). The rules are
validated with `promtool check rules ops/prometheus/alerts.yml`. Thresholds are starting points: tune them to your
traffic and route the alerts with Alertmanager.

## Tracing

Requests are traced with OpenTelemetry.

- **Logs:** trace and span ids appear in every log line.
- **Responses:** every response carries the trace id in `X-Trace-Id`, so a client's error report leads straight to the
  matching logs.
- **Sampling:** `TRACING_SAMPLING_PROBABILITY` sets the share of requests traced (`1.0` locally, `0.1` in prod).
- **Export:** traces are exported only once an OTLP endpoint is configured, for example
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces` for Jaeger, Tempo or any
  OpenTelemetry collector.

## Logs

Logs are readable text locally. In the prod profile, each line is one JSON object in the Elastic Common Schema, ready
for Elasticsearch, Loki or any log pipeline.

```json
{"@timestamp":"2026-10-19T21:50:30.821Z","log":{"level":"INFO","logger":"com.hikmethankolay.template.Application"},
 "service":{"name":"spring-boot-template"},"message":"Started Application in 4.1 seconds"}
```

## Audit log

Security events go to the `audit` logger as structured entries. See [Security](../security/#audit-log) for the event
list and fields. Route that logger to storage with a longer retention than application logs.

## Email outbox

Emails are not sent from the request directly. They are stored in the `email_outbox` table in the same transaction as
the change that triggers them, so an email is sent only if the change commits, and a restart or SMTP outage never
loses it:

1. Right after the commit, the email is sent once.
2. If SMTP fails, it is retried every `MAIL_OUTBOX_POLL_INTERVAL` with exponential backoff from `MAIL_RETRY_DELAY`
   (30 s, 1 min, 2 min, … up to 1 hour).
3. After `MAIL_MAX_ATTEMPTS` attempts it is dropped, an error is logged and `mail_outbox_dropped_total` grows.

Rows are deleted once sent or dropped, so links with tokens do not stay in the database. Several instances can send at
the same time: rows are locked with `FOR UPDATE SKIP LOCKED`, so no email goes out twice. Use `EmailOutbox.enqueue`
for your own emails.

An email rendered from a template carries a text body and an HTML body (`html_body`) and is sent as
`multipart/alternative`; one queued with only a text body is sent as plain text. See
[Email templates](../email-templates/).

Broadcast emails are not sent right after the commit: each scheduled run sends them after every other email that
is due, so a large broadcast never delays a password reset. `mail_outbox_pending`, and the `EmailBacklog` alert on
it, leave them out.

## Health

`/actuator/health`, with Kubernetes-style `liveness` and `readiness` groups. Redis and mail are excluded on purpose,
because the app keeps working without them.
