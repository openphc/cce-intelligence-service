# Data Dictionary

This service uses one shared Postgres database (`ccedb`) for everything — one table it reads (owned by another service), one table and one index it owns.

## `inbound_event_log` (read-only — owned by `cce-collector-service`)

This service does **not** migrate or write this table. It exists via `collector-service`'s own Flyway history (`flyway_schema_history_collector`) on the same shared `ccedb` instance. Only the columns this service actually reads:

| Column | Type | Used for |
|---|---|---|
| `received_at` | `TIMESTAMPTZ` | `IngestionGapEvaluator` reads `MAX(received_at)` — the last successful transaction time, and the basis for both the escalation `reference_key` and the "hours since last success" calculation. |

The table has `ALTER TABLE inbound_event_log REPLICA IDENTITY FULL` set (for CDC into the analytics pipeline elsewhere) — irrelevant to this service directly, but confirms this is the authoritative, zero-lag source rather than a lagged copy.

**Access required:** a read-only grant for this service's DB user on this one table. No write access, no other tables in `collector-service`'s schema.

## `notification_tracker` (owned by this service)

One row per incident occurrence, for any alert type — not ingestion-specific, even though `INGESTION_GAP` is the only alert type registered today.

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` (PK) | `gen_random_uuid()` |
| `alert_type` | `VARCHAR(64)` | What condition is being monitored — `'INGESTION_GAP'` today. Not named `notification_type`, to avoid reading as "channel" (channel is per-tier config, not a column here). |
| `reference_key` | `VARCHAR(255)` | A **deterministic** key the evaluator computes — not a random ID. For `INGESTION_GAP`: the timestamp of the last successful transaction before the gap began. Invariant for the whole lifetime of one incident (see flow-diagrams.md for why this matters for concurrency safety). Internal/machine-facing — not what the email shows; see `incident_id`. |
| `incident_id` | `VARCHAR(50)` | The human-facing ID shown in the email (`CCE-TXN-YYYYMMDD-NN`, matching the PRD). Assigned once, only when the row is first created (`NotificationTrackerRepository.claimAndLock`) — a per-day, per-`alert_type` sequence computed from a `COUNT(*)` of that day's rows at insert time, not derived from `reference_key` or any timestamp, and never recomputed afterward. See api-reference.md for why this is a separate column from `reference_key` rather than the same value. |
| `status` | `VARCHAR(20)` | `'ACTIVE'` \| `'RESOLVED'` |
| `current_tier` | `SMALLINT` | An ordinal pointer into that `alert_type`'s configured tier list (`application.yml`) — not a fixed 1–3 scale. A future alert type with a different tier count just uses a different top end. |
| `opened_at` | `TIMESTAMPTZ` | When tier 1 was sent. |
| `last_notified_at` | `TIMESTAMPTZ` | Updated only on a successful send. Doubles as "when was the previous tier sent" for templates that need it (tier 3's email shows tier 2's send time) — read before it's overwritten for the current send. |
| `resolved_at` | `TIMESTAMPTZ` | Null while `ACTIVE`. |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | Standard audit columns. |

```sql
CREATE TABLE notification_tracker (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    alert_type          VARCHAR(64)  NOT NULL,
    reference_key       VARCHAR(255) NOT NULL,
    incident_id         VARCHAR(50),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    current_tier        SMALLINT     NOT NULL DEFAULT 0,
    opened_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_notified_at    TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT notification_tracker_pkey PRIMARY KEY (id),
    CONSTRAINT notification_tracker_status_check CHECK (status IN ('ACTIVE', 'RESOLVED'))
);

-- At most one ACTIVE tracker per (alert_type, occurrence) — also doubles as the
-- concurrency guard against two pods both creating the first row for a new incident.
CREATE UNIQUE INDEX uq_notification_tracker_active
    ON notification_tracker (alert_type, reference_key)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_notification_tracker_type_status
    ON notification_tracker (alert_type, status);
```

**Deliberately absent:** no `metadata`/JSONB column, no dispatch-log table. Everything the current templates need is derivable from the columns above (see architecture-overview.md's reasoning if this needs revisiting for a future alert type that needs a genuine point-in-time snapshot).

## Migration ownership

This table is created by this service's own Flyway migration, `V1__notification_tracker.sql`, tracked under its own `flyway_schema_history` table on the shared `ccedb` — same one-schema-per-service convention every other service on this database already follows (`flyway_schema_history_collector`, `_compliance`, `_scheduler`, etc.).
