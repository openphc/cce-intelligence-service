# Insights Pre-Computation Optimization

> **CCE Intelligence Service** — Pre-computed delivery metrics for the Insights Service  
> **Status**: Proposed | **Target**: v1.2.0  
> **Last Updated**: 2025-05-28  
> **Deployment Model**: Fresh deployment (no existing data to migrate)

---

## 1. Problem Statement

The Intelligence Service tracks webhook delivery lifecycle in the `intelligence_delivery` table (one row per trigger event × destination adaptor). At production scale, this table grows at the same rate as intelligence triggers — potentially thousands of rows per day. The `intelligence_delivery_audit_log` grows even faster (3–5 audit rows per delivery).

The Insights Service currently has **no intelligence analytics endpoints**. However, a complete operational dashboard requires:

- **Delivery success/failure rates** — by destination, action type, severity, time period
- **Webhook latency distribution** — which adaptors are slow or degrading?
- **Delivery volume trends** — are intelligence actions firing at expected rates?
- **Adaptor health** — which receiver adaptors have elevated failure rates?
- **End-to-end intelligence pipeline** — from deviation detection (Compliance) to delivery (Intelligence)

Without pre-computation, the Insights Service would need to run `GROUP BY` aggregations on the full `intelligence_delivery` table with JOINs to `destination_adaptor_mapping` and `receiver_adaptor` — reproducing the same scaling problems already identified in Collector and Compliance service tables.

### 1.1 Design Constraint: No Core Table Denormalization

> **Customer directive:** Core operational tables must NOT be modified for insights purposes (e.g., no adding `facility_id` to `intelligence_delivery`). Instead, all insights data is served from **separate pre-computed tables** that are updated incrementally in real-time by service code.
>
> These pre-computed tables are **temporary** — they will be replaced by a dedicated data pipeline in a future release. The tables must be fully self-contained and droppable without affecting core delivery functionality.

### 1.2 Anticipated Insights Queries

| Query Category | Tables Involved | Expected Complexity |
|----------------|-----------------|---------------------|
| Delivery funnel (PENDING → DELIVERED/FAILED) | `intelligence_delivery` | `GROUP BY status` on full table |
| Delivery rate by destination | `intelligence_delivery` JOIN `destination_adaptor_mapping` | 2-table JOIN + GROUP BY |
| Delivery rate by severity/action type | `intelligence_delivery` | `GROUP BY severity, action_type` |
| Delivery trends over time | `intelligence_delivery` | `DATE_TRUNC + GROUP BY` on growing table |
| Adaptor health (failure rate) | `intelligence_delivery` JOIN `destination_adaptor_mapping` JOIN `receiver_adaptor` | 3-table JOIN + conditional aggregates |
| Average webhook latency | `intelligence_delivery` | `AVG(delivered_at - created_at)` |
| Failed delivery details | `intelligence_delivery` + `intelligence_delivery_audit_log` | JOIN + filter |
| Retry distribution | `intelligence_delivery` | `GROUP BY attempt_count` |

---

## 2. Optimizations Owned by Intelligence Service

### 2.1 New Table: `delivery_summary_daily`

**Problem:** Every dashboard query for delivery metrics would scan the full `intelligence_delivery` table. Delivery status distribution, success rates, and volume by destination/severity are the most common analytics queries, but they only change when new deliveries complete.

**Solution:** Maintain a pre-aggregated daily summary table, updated incrementally on each delivery status change (DELIVERED or FAILED). The `facility_id` is captured from the Kafka trigger event headers (`ce_facilityid`) at creation time — eliminating the need to add it to `intelligence_delivery`.

**Schema:**

```sql
CREATE TABLE delivery_summary_daily (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    summary_date        DATE NOT NULL,
    facility_id         VARCHAR(100),            -- captured from trigger event headers
    destination         VARCHAR(200) NOT NULL,
    receiver_adaptor_id UUID,                    -- resolved from mapping at delivery time
    action_type         VARCHAR(30) NOT NULL,     -- CommunicationRequest, Task, ServiceRequest
    severity            VARCHAR(20) NOT NULL,     -- LOW, MEDIUM, HIGH, CRITICAL
    total_count         BIGINT NOT NULL DEFAULT 0,
    delivered_count     BIGINT NOT NULL DEFAULT 0,
    failed_count        BIGINT NOT NULL DEFAULT 0,
    cancelled_count     BIGINT NOT NULL DEFAULT 0,
    total_attempts      BIGINT NOT NULL DEFAULT 0, -- sum of attempt_count across deliveries
    total_latency_ms    BIGINT NOT NULL DEFAULT 0, -- sum of (delivered_at - created_at) in ms
    min_latency_ms      BIGINT,
    max_latency_ms      BIGINT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (summary_date, facility_id, destination, action_type, severity)
);

CREATE INDEX idx_delivery_daily_date ON delivery_summary_daily (summary_date);
CREATE INDEX idx_delivery_daily_facility ON delivery_summary_daily (facility_id);
CREATE INDEX idx_delivery_daily_dest ON delivery_summary_daily (destination);
CREATE INDEX idx_delivery_daily_adaptor ON delivery_summary_daily (receiver_adaptor_id);
```

**Update trigger:** In `ActionDispatcher`, after Phase 3 (delivery result update), upsert the summary row:

```sql
-- On delivery creation (PENDING)
INSERT INTO delivery_summary_daily (summary_date, facility_id, destination, receiver_adaptor_id, action_type, severity, total_count)
VALUES (:date, :facilityId, :destination, :adaptorId, :actionType, :severity, 1)
ON CONFLICT (summary_date, facility_id, destination, action_type, severity)
DO UPDATE SET
    total_count = delivery_summary_daily.total_count + 1,
    updated_at = now();

-- On delivery success (DELIVERED)
UPDATE delivery_summary_daily SET
    delivered_count = delivered_count + 1,
    total_attempts = total_attempts + :attemptCount,
    total_latency_ms = total_latency_ms + :latencyMs,
    min_latency_ms = LEAST(min_latency_ms, :latencyMs),
    max_latency_ms = GREATEST(max_latency_ms, :latencyMs),
    updated_at = now()
WHERE summary_date = :date AND facility_id IS NOT DISTINCT FROM :facilityId
  AND destination = :destination AND action_type = :actionType AND severity = :severity;

-- On delivery failure (FAILED)
UPDATE delivery_summary_daily SET
    failed_count = failed_count + 1,
    total_attempts = total_attempts + :attemptCount,
    updated_at = now()
WHERE summary_date = :date AND facility_id IS NOT DISTINCT FROM :facilityId
  AND destination = :destination AND action_type = :actionType AND severity = :severity;
```

**Insights Service query enablement:**

| Analytics Query | SQL on Pre-Computed Table |
|----------------|--------------------------|
| Delivery funnel | `SELECT SUM(total_count), SUM(delivered_count), SUM(failed_count), SUM(cancelled_count) FROM delivery_summary_daily WHERE summary_date BETWEEN ? AND ?` |
| Success rate by destination | `SELECT destination, SUM(delivered_count)::float / NULLIF(SUM(total_count), 0) * 100 FROM delivery_summary_daily GROUP BY destination` |
| Success rate by severity | `SELECT severity, SUM(delivered_count)::float / NULLIF(SUM(total_count), 0) * 100 FROM delivery_summary_daily GROUP BY severity` |
| Volume trends | `SELECT summary_date, SUM(total_count) FROM delivery_summary_daily GROUP BY summary_date ORDER BY summary_date` |
| Avg webhook latency by destination | `SELECT destination, SUM(total_latency_ms)::float / NULLIF(SUM(delivered_count), 0) FROM delivery_summary_daily GROUP BY destination` |
| Retry pressure (avg attempts) | `SELECT destination, SUM(total_attempts)::float / NULLIF(SUM(total_count), 0) FROM delivery_summary_daily GROUP BY destination` |
| Volume by action type | `SELECT action_type, SUM(total_count) FROM delivery_summary_daily GROUP BY action_type` |
| Deliveries by facility | `SELECT facility_id, SUM(total_count), SUM(delivered_count) FROM delivery_summary_daily WHERE facility_id IS NOT NULL GROUP BY facility_id` |

---

### 2.2 New Table: `adaptor_health_snapshot`

**Problem:** Determining adaptor health requires joining `intelligence_delivery` with `destination_adaptor_mapping` and `receiver_adaptor`, then computing failure rates over a rolling window. This is a 3-table JOIN with conditional aggregates on a growing table.

**Solution:** Maintain a per-adaptor health snapshot, updated on every delivery outcome.

**Schema:**

```sql
CREATE TABLE adaptor_health_snapshot (
    receiver_adaptor_id UUID PRIMARY KEY,
    adaptor_name        VARCHAR(200) NOT NULL,
    active_destinations INTEGER NOT NULL DEFAULT 0,
    total_deliveries    BIGINT NOT NULL DEFAULT 0,
    successful_deliveries BIGINT NOT NULL DEFAULT 0,
    failed_deliveries   BIGINT NOT NULL DEFAULT 0,
    success_rate        NUMERIC(5,2),             -- (successful / total) * 100
    avg_latency_ms      BIGINT,                   -- average webhook response time
    last_delivery_at    TIMESTAMPTZ,
    last_failure_at     TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0, -- reset on success
    health_status       VARCHAR(20) NOT NULL DEFAULT 'HEALTHY',  -- HEALTHY, DEGRADED, UNHEALTHY
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Health status rules:**

| Condition | Status |
|-----------|--------|
| `success_rate >= 95%` AND `consecutive_failures < 3` | `HEALTHY` |
| `success_rate >= 80%` OR `consecutive_failures BETWEEN 3 AND 9` | `DEGRADED` |
| `success_rate < 80%` OR `consecutive_failures >= 10` | `UNHEALTHY` |

**Update trigger:** In `ActionDispatcher`, after Phase 3:

- **On DELIVERED:**
  - `successful_deliveries += 1`, `total_deliveries += 1`
  - `consecutive_failures = 0`
  - Recalculate `success_rate` and `avg_latency_ms`
  - Update `last_delivery_at`, re-evaluate `health_status`

- **On FAILED:**
  - `failed_deliveries += 1`, `total_deliveries += 1`
  - `consecutive_failures += 1`
  - Recalculate `success_rate`
  - Update `last_failure_at`, re-evaluate `health_status`

**Insights Service query enablement:**

| Analytics Query | SQL on Pre-Computed Table |
|----------------|--------------------------|
| Adaptor health dashboard | `SELECT * FROM adaptor_health_snapshot ORDER BY success_rate ASC` |
| Unhealthy adaptors alert | `SELECT * FROM adaptor_health_snapshot WHERE health_status = 'UNHEALTHY'` |
| Adaptor ranking by latency | `SELECT adaptor_name, avg_latency_ms FROM adaptor_health_snapshot ORDER BY avg_latency_ms DESC` |
| Adaptors with recent failures | `SELECT * FROM adaptor_health_snapshot WHERE last_failure_at > now() - interval '1 hour'` |

---

## 3. Consistency Guarantees

- **`delivery_summary_daily`** — Updated synchronously in the same transaction as the delivery status update (Phase 3 of `ActionDispatcher`). No consistency lag between delivery table and summary.
- **`adaptor_health_snapshot`** — Updated synchronously on each delivery outcome. Health status is always current with the latest delivery result.
- **`facility_id` in summary** — Captured at delivery creation time from the trigger event Kafka headers. Available for all facility-scoped analytics without modifying `intelligence_delivery`.

### 3.1 No Backfill Required

Since this is a fresh deployment with no existing data, all optimizations are included in the initial schema from day 1. Summary tables and health snapshots populate organically as deliveries are processed. No backfill migrations are needed.

---

## 4. Summary of Changes

| Change | Type | Table | Updated By | Trigger |
|--------|------|-------|-----------|---------|
| New `delivery_summary_daily` table | Table | New | Intelligence | Delivery create/complete/fail |
| New `adaptor_health_snapshot` table | Table | New | Intelligence | Delivery complete/fail |

> **Note:** No columns are added to the `intelligence_delivery` table. The `intelligence_delivery` schema remains unchanged. Facility-scoped analytics are served from `delivery_summary_daily.facility_id`.

### 4.1 Flyway Migration Plan (Fresh Deployment)

All optimizations are included in the initial schema:

| Order | Migration | Description |
|-------|-----------|-------------|
| V1 | `V1__initial_schema.sql` | All core tables with original schema (no insights columns) |
| V2 | `V2__create_delivery_summary_daily.sql` | Pre-computed delivery summary table (includes facility_id) |
| V3 | `V3__create_adaptor_health_snapshot.sql` | Pre-computed adaptor health snapshot table |

### 4.2 Metrics Additions

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `cce.intelligence.adaptor.health` | Gauge | `adaptor_name`, `status` | 1 = healthy, 0.5 = degraded, 0 = unhealthy |
| `cce.intelligence.adaptor.consecutive_failures` | Gauge | `adaptor_name` | Current consecutive failure count |

---

## 5. Cross-Service Dependencies

### 5.1 Dead Column: intelligence_event_log.error_message (Compliance Service §2.5)

The `intelligence_event_log` table (owned by Compliance Service, written by `IntelligenceActionEvaluator`) originally had a declared `error_message` TEXT column that was **never populated**.

**Action (fresh deploy):** This column is simply **not included** in the initial `intelligence_event_log` DDL (see **Compliance Service §2.5**). The Intelligence Service does not read or write this column, so no code changes are required.

---

## 6. Future: Data Pipeline Replacement

All pre-computed tables defined in this document are **temporary**. They will be replaced by a dedicated data pipeline in a future release. When the data pipeline is implemented:

1. Drop the pre-computed tables (`delivery_summary_daily`, `adaptor_health_snapshot`)
2. Remove the corresponding repository, entity, and service code
3. Core `intelligence_delivery` table remains completely unchanged — no rollback needed
4. The Insights Service switches from querying pre-computed tables to querying the data warehouse
