# Developer Setup

## Prerequisites

- Java 21
- A reachable Postgres instance with the shared `ccedb` database (the `deploy-scripts` docker-compose stack provides this — `cce-postgres`), and a DB user with:
  - Read access to `collector-service`'s `inbound_event_log` table
  - Full access (own schema/Flyway history) for this service's own migration
- An SMTP account for sending real test emails (Gmail with an App Password works — see below)

## Environment variables

| Variable | Purpose | Local example |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | Shared Postgres connection | `localhost` / `5432` / `ccedb` |
| `DB_USERNAME` / `DB_PASSWORD` | Shared Postgres credentials | matches `deploy-scripts`' `.env` |
| `SPRING_MAIL_HOST` | SMTP host | `smtp.gmail.com` |
| `SPRING_MAIL_PORT` | SMTP port | `587` |
| `SPRING_MAIL_USERNAME` | SMTP auth user | your test Gmail address |
| `SPRING_MAIL_PASSWORD` | SMTP auth password (a Gmail **App Password**, not your account password) | — |
| `ALERT_EMAIL_TIER1` / `ALERT_EMAIL_TIER2` / `ALERT_EMAIL_TIER3` | Tier recipient addresses — named by tier position, not by whoever currently holds it, so the variable name never goes stale when a person changes | for local testing, all three can point at one inbox |
| `ALERT_NAME_TIER1` / `ALERT_NAME_TIER2` / `ALERT_NAME_TIER3` | Tier recipient **names** — used in the email greeting and any cross-tier mentions, e.g. "will notify Claudel" | required, no default — startup fails with a clear error naming the missing var if unset (see api-reference.md) |
| `CCE_OPSALERT_TIER1_CC` / `_TIER2_` / `_TIER3_` | Extra Cc recipients for each tier | optional — every tier defaults to none, no built-in escalation chain (a chain default used to exist for tiers 2/3, removed as a real trap once exposed as a configurable env var — see api-reference.md) |
| `CCE_OPSALERT_TIER1_THRESHOLD_MINUTES` / `_TIER2_` / `_TIER3_` | Minutes before each tier fires | required, no default — startup fails with a clear error naming the exact property/value if unset (Spring's own numeric binding does this for free; see api-reference.md) |
| `CCE_OPSALERT_TIER1_SUBJECT` / `_TIER2_` / `_TIER3_` | Email subject line for each tier — may contain the literal token `{duration}`, substituted with that tier's own threshold as natural language | optional — defaults to the current copy, only set to override |
| `CCE_OPSALERT_NOTIFICATIONS_ENABLED` | Fully-silent kill switch — `false` skips the scheduled tick entirely (no evaluation, no tracker state, nothing sent) | optional, defaults to `true`; see api-reference.md's "Operational controls" for why it's silent rather than a "mute but keep tracking" switch |
| `CCE_OPSALERT_REPEAT_INTERVAL_MINUTES` | Resends the last tier's email every this-many minutes (from whichever send most recently happened) once escalation reaches it, until the incident resolves | optional, defaults to `1440` (24h — on by default; set `<= 0` explicitly to disable); see api-reference.md's "Operational controls" |

`ALERT_EMAIL_TIER*` and `CCE_OPSALERT_TIER*_CC` both accept a single address or a comma-separated list (`a@x.com,b@x.com`) — see api-reference.md for how that's parsed and validated.

**Never commit real SMTP credentials.** `application.yml` only ever references `${SPRING_MAIL_PASSWORD}` etc. — set the real values via a local, git-ignored `application-local.yml` (already covered by `.gitignore`) or your IDE's run configuration environment variables, never by editing the tracked `application.yml`/`application-docker.yml` directly.

## Running locally

Two ways to run it — same config either way, different mechanism for supplying it.

### Option A: `./gradlew bootRun` (fastest inner loop)

```bash
export DB_HOST=localhost DB_PORT=5432 DB_NAME=ccedb DB_USERNAME=... DB_PASSWORD=...
export SPRING_MAIL_HOST=smtp.gmail.com SPRING_MAIL_PORT=587
export SPRING_MAIL_USERNAME=... SPRING_MAIL_PASSWORD=...
export ALERT_EMAIL_TIER1=you@example.com ALERT_EMAIL_TIER2=you@example.com ALERT_EMAIL_TIER3=you@example.com
export ALERT_NAME_TIER1=Patience ALERT_NAME_TIER2=Claudel ALERT_NAME_TIER3=Andrew
export CCE_OPSALERT_TIER1_THRESHOLD_MINUTES=240 CCE_OPSALERT_TIER2_THRESHOLD_MINUTES=1440 CCE_OPSALERT_TIER3_THRESHOLD_MINUTES=2880
./gradlew bootRun --args='--spring.profiles.active=local'
```

Flyway runs automatically on startup and creates `notification_tracker` in `ccedb`. (The `local` profile's `application-local.yml` sets its own concrete `to`/`to-name`/`threshold-minutes` values directly and doesn't actually need the exports above — they're shown here for running without that profile.)

### Option B: `docker compose up` (closer to how it actually deploys)

```bash
cp .env.example .env   # fill in real SMTP + recipient values — .env is git-ignored
docker compose up -d --build
```

Builds the image from the repo's `Dockerfile` (a standard multi-stage build — compiles from source in a JDK image, then a slim JRE runtime image, same shape as the other CCE services' Dockerfiles) and runs it on `localhost:8092` (mapped to the container's `8085`). **Not self-contained** — unlike some sibling services' compose files, this one doesn't bring up its own fresh Postgres, because this service reads `collector-service`'s own `inbound_event_log` table (`IngestionGapEvaluator`, architecture-overview.md), and a brand-new empty Postgres wouldn't have that table at all. It connects instead to the same already-running shared Postgres `collector-service`'s own compose stack creates (`cce-collector-postgres`, network `cce-collector-service_default`) — so that stack needs to already be up first.

The compose file's `build.network: host` setting is there for sandboxed/restricted environments that block the isolated network Docker's build step normally uses — without it, `./gradlew dependencies` inside the build stage can fail to reach `services.gradle.org` at all. Harmless in a normal environment; only affects the build stage's own dependency-fetching, nothing about the running container. Confirmed empirically this setting is only honored by Docker's classic builder, not the BuildKit builder `docker compose build` defaults to — if the build fails with that same network timeout despite the setting being present, force the classic builder: `DOCKER_BUILDKIT=0 docker compose build && docker compose up -d`.

## Seeing all 3 tiers fire without waiting real hours

Production tier thresholds are `4h`/`24h`/`48h`, matching the PRD. For local verification, the `local` profile overrides these to a few minutes instead — see `application-local.yml`'s `cce.opsalert.alert-types.INGESTION_GAP.tiers[*].threshold-minutes` (minutes, not hours, to avoid fractional values at test scale; the escalation logic doesn't know or care about the actual magnitude).

1. Start the service with the `local` profile active and a stale (or empty) `inbound_event_log`, so the gap is already large.
2. Watch the logs / your test inbox — with minute-scale thresholds and a 30-second (or shortened, see `cce.opsalert.poll-interval-ms` in `application-local.yml`) poll interval, tier 1 fires on the next tick, tier 2 and 3 follow as the gap keeps growing.
3. Insert a fresh row into `inbound_event_log` (or run the actual collector pipeline) to confirm the tracker closes and a later gap opens a *new* incident from tier 1.

## Verifying concurrency safety locally

Run two instances against the same `ccedb` (different ports), both with the same tier config, and confirm only one dispatch happens per tier crossing — the second instance's tick should log a no-op, not a duplicate send. This exercises the row-lock claim described in flow-diagrams.md, not just that the code compiles. (The same guarantee is also covered by an automated test — see below — this manual version is for cross-checking against the real running service.)

## Tests

Two source sets, mirroring `cce-scheduler-service`'s convention:

- **`./gradlew test`** — fast unit tests (`src/test/java`), no external services. Covers `TierConfig`'s validation (every config-mistake class found and fixed during RI-63's development: missing/unresolved env vars, malformed addresses, missing `channel`/`subject`/`template`), `AlertTypeConfig`, `AlertEvaluation`, `EmailDispatcher` (From/To/Cc/subject/body on the actual `MimeMessage`, including the From-address fix — api-reference.md), and `AlertEscalationEngine`'s `notifications-enabled` kill switch (both states, plus the `cce.opsalert.tick.skipped` counter).
- **`./gradlew integrationTest`** — Testcontainers-backed (`src/integrationTest/java`), spins up a real Postgres per test class (not H2 — `NotificationTrackerRepository`'s SQL depends on genuinely Postgres-specific behavior: a partial unique index as an `ON CONFLICT` target, `SELECT ... FOR UPDATE` locking, `to_char`). Covers:
  - `NotificationTrackerRepositoryIT` — dedup via `claimAndLock`, the `incident_id` sequence (including that it's independent per `alert_type`), `closeStale`, and a real concurrent-claim test (8 threads racing to create the same tracker, asserting exactly one row results) — the automated version of the manual two-instance check above.
  - `IngestionGapEvaluatorIT` — healthy/unhealthy detection, the `referenceKey`/threshold boundary.
  - `AlertEscalationEngineIT` — the full tick end-to-end (a fake `AlertEvaluator` + a recording fake `NotificationDispatcher`, but the real repository, real transaction boundaries, and the real Thymeleaf templates): tier 1 firing once, tier 2/3 firing only once their threshold has elapsed *since tier 1's send* (simulated by backdating `opened_at` directly rather than sleeping), recovery closing the tracker, a dispatch failure rolling back and retrying, and the `closeStale` missed-recovery scenario from flow-diagrams.md.

**Requires Docker.** If `integrationTest` fails immediately with `Could not find a valid Docker environment` / `client version ... is too old`, Testcontainers' bundled `docker-java` client is failing to negotiate with your Docker Engine's minimum supported API version — a known class of issue against very new Docker releases, not something wrong with the tests themselves. Things to try, in order: confirm `docker ps` works directly first; if it does, try setting `DOCKER_API_VERSION` to a value your Docker Engine reports as supported (`docker version --format '{{.Server.APIVersion}}'`) — the `integrationTest` Gradle task already sets this to `1.44` by default, adjust it if your daemon needs something else. If the client-version error persists regardless of what's configured, that's evidence `docker-java` itself is hardcoding an old probe version irrespective of configuration (observed on at least one very new/non-standard Docker Engine build) — a genuine tooling gap, not a project misconfiguration. This has not been observed against standard CI Docker Engine builds (e.g. GitHub Actions runners).
