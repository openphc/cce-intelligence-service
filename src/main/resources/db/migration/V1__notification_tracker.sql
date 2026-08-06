-- RI-63: operational notification engine — see docs/data-dictionary.md and docs/architecture-overview.md.
-- One row per incident occurrence, for any alert_type (INGESTION_GAP is the only one registered today).

CREATE TABLE notification_tracker (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    alert_type          VARCHAR(64)  NOT NULL,
    reference_key       VARCHAR(255) NOT NULL,
    -- Human-facing ID matching the PRD's format (CCE-TXN-YYYYMMDD-NN), assigned once when a
    -- tracker row is first created and never recomputed after — see NotificationTrackerRepository.
    -- Deliberately a separate column from reference_key: reference_key must stay a value the
    -- evaluator can recompute identically on every poll tick (that's what makes the dedup/
    -- concurrency logic in claimAndLock correct), whereas incident_id's sequence number depends
    -- on how many trackers already opened today, which requires a DB read the evaluator doesn't
    -- have access to by design (docs/architecture-overview.md). Both columns live on the same
    -- row, so tracing an emailed incident ID back to its tracker row — or vice versa — works
    -- directly either way.
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

-- At most one ACTIVE tracker per (alert_type, occurrence) — also doubles as the concurrency
-- guard against two pods both creating the first row for a new incident (docs/flow-diagrams.md).
CREATE UNIQUE INDEX uq_notification_tracker_active
    ON notification_tracker (alert_type, reference_key)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_notification_tracker_type_status
    ON notification_tracker (alert_type, status);
