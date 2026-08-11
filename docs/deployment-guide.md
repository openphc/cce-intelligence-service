# Deployment Guide

## Infrastructure dependencies

- **Shared Postgres (`ccedb`)** — this service's only datastore. Needs its own Flyway-migrated schema (auto-created on startup, `V1__notification_tracker.sql`) plus a **read-only grant on `collector-service`'s `inbound_event_log` table** (not owned by this service — see data-dictionary.md).
- **SMTP** — any standard SMTP provider. Gmail with an App Password works for non-production testing; a real deployment should use whatever transactional-email provider the platform already trusts. **The authenticated account must be allowed to send as itself** — `EmailDispatcher` sets `From` to `SPRING_MAIL_USERNAME` (api-reference.md), which is always true for the account's own mailbox, but confirm before deploying against a provider that enforces `Send As` restrictions (Office365 rejected the previous unset-From behavior outright with `554 5.2.252 SendAsDenied`).
- No Kafka dependency, no ClickHouse dependency, no dependency on `data-pipeline`'s CDC — deliberately, see architecture-overview.md.

## Environment variables (production shape)

| Variable | Notes |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Shared `ccedb` connection |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | SMTP |
| `ALERT_EMAIL_TIER1`, `ALERT_EMAIL_TIER2`, `ALERT_EMAIL_TIER3` | Real tier recipient addresses in production, named by tier position rather than by whoever currently holds it — **in this branch, all three currently point at one test address for verification, not the real distribution list; update before this goes anywhere near production traffic.** Required, no default — missing one fails startup. |
| `ALERT_NAME_TIER1`, `ALERT_NAME_TIER2`, `ALERT_NAME_TIER3` | Recipient names shown in the email greeting/body — parameterized alongside the addresses, and named the same tier-position way, so a name *or role* change is a config update, never a template edit (see api-reference.md). Also required, no default. |
| `CCE_OPSALERT_TIER1_THRESHOLD_MINUTES`, `_TIER2_`, `_TIER3_` | Minutes before each tier fires. Required, no default (same philosophy as the recipients above, not the subject/cc below) — missing one fails startup with a precise error naming the exact property, value, and YAML line (Spring's own numeric binding does this for free, no custom validation code needed; see api-reference.md) |
| `CCE_OPSALERT_TIER1_CC`, `_TIER2_`, `_TIER3_` | Optional — all three default to no Cc; set one explicitly to add recipients for that tier |
| `CCE_OPSALERT_POLL_INTERVAL_MS` | Defaults to `300000` (5 minutes) |
| `CCE_OPSALERT_TIER1_SUBJECT`, `_TIER2_`, `_TIER3_` | Optional — defaults to the current subject copy for each tier; set only to override |
| `CCE_OPSALERT_NOTIFICATIONS_ENABLED` | Fully-silent kill switch, default `true`. Set to `false` to skip the scheduled tick entirely — no evaluation, no tracker state, nothing dispatched (api-reference.md, "Operational controls"). Not the same as a "mute but keep watching" switch — nothing that happens while it's off is caught up on once it's back on. |

Never bake real SMTP credentials or recipient addresses into a committed YAML file — inject via the deployment platform's secret mechanism (Kubernetes `Secret` / `deploy-scripts`' existing `cce-secrets` pattern), matching how every other credential in this platform is handled.

### Adding or removing a recipient on an already-deployed instance

`ALERT_EMAIL_TIER1`/`_TIER2`/`_TIER3` (the `to`) and `CCE_OPSALERT_TIER1_CC`/`_TIER2_`/`_TIER3_` (the `cc`) each accept one address **or** a comma-separated list (`a@x.com,b@x.com`) — see api-reference.md. So the actual operator workflow is:

1. Update the value in wherever the secret is sourced from (Infisical, the k8s `Secret`, `.env` — not the tracked `application.yml`).
2. Restart the pod(s) so the new env var value is picked up — Spring reads env vars at startup, not on a live reload, so this is a rollout restart (`kubectl rollout restart deployment/cce-intelligence-service`), not a rebuild or a code deploy. No YAML file in this repo needs editing, and no new image needs building.

If the value is malformed (a stray comma, a typo), the pod fails to start with a clear error naming the exact bad value (`TierConfig`'s startup validation, api-reference.md) rather than starting and silently failing every send afterward — so a bad edit is caught by the restart itself, not discovered later from a missing email.

`CCE_OPSALERT_TIERn_CC` has the same default for every tier — empty, no Cc. There's no built-in escalation chain to be aware of: if tier 2's email should also reach tier 1's recipient, that address needs to be in `CCE_OPSALERT_TIER2_CC` explicitly, same as configuring any other recipient.

## Concurrency / autoscaling implications

If this service runs behind a Kubernetes `HorizontalPodAutoscaler` (as several services on this platform already do — `cce-collector-service`, `cce-compliance-service`, `cce-intelligence-service`'s own prod config, and `cce-insights-service` all have HPAs in `deploy-scripts/k8s/overlays/prod/cce-hpas.yaml`), the row-locking design (flow-diagrams.md) is what makes multiple replicas safe — every pod polls independently, and Postgres row locks prevent duplicate sends. No leader-election component is required, but the mail sender's connection/read timeout **must** be bounded (see below) since a hung SMTP call holds a database connection and row lock for its duration.

## `deploy-scripts` wiring (done — noted here for what to check if it ever drifts)

This service's env vars (SMTP, `ALERT_EMAIL_*`/`ALERT_NAME_*`, thresholds, Cc, `CCE_OPSALERT_NOTIFICATIONS_ENABLED`) are wired in `deploy-scripts`' `k8s/base/services/cce-intelligence-service.yaml` and both `k8s/overlays/{uat,prod}/kustomization.yaml`, sourced from Infisical. `spring.mail.properties.mail.smtp.connectiontimeout`/`.timeout`/`.writetimeout` are set directly in this repo's `application.yml`, not deploy-scripts — they're load-bearing for the row-lock design (flow-diagrams.md has the worked example), so they're not meant to vary per environment.

The one thing that's genuinely external to this repo and still worth confirming per-environment: a read-only Postgres grant for this service's DB user on `collector-service`'s `inbound_event_log` table (a one-line `GRANT SELECT` in whatever DB-provisioning script/migration owns cross-service grants). Without it, `IngestionGapEvaluator` fails its query every tick rather than failing to start — the service still comes up, it just never reports anything but errors.

## Health checks

`GET /actuator/health` reflects Postgres connectivity, **not** SMTP reachability — the mail health indicator is explicitly disabled (`management.health.mail.enabled: false`, api-reference.md's "Observability surface"), because it was found live in UAT to take an otherwise-healthy pod down on a slow Office365 connection check that ran on every single probe hit. A deployment probe pointed at `/actuator/health` is sufficient — there's no separate readiness concern specific to the ops-alerting logic itself (it degrades gracefully: if the DB is briefly unreachable, a tick simply fails and retries on the next scheduled run; if SMTP is briefly unreachable, a dispatch attempt fails, the tracker rolls back, and the same tier retries next tick — see flow-diagrams.md step 5 — none of which should ever be a reason to restart the pod).
