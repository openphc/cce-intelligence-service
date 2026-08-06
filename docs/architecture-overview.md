# Architecture Overview

## What this is

A generic **operational notification engine**. A scheduled evaluator watches a signal, a tracker table owns exactly how far an incident has escalated, and a pluggable dispatcher sends the message on whatever channel is configured. RI-63's ingestion-gap alert (no successful eBuzima-to-HIE transaction for 4h/24h/48h) is the first thing plugged into it — three tiers, escalating from Patience (HIE Engineer) to Claudel (MOH Digital PM, cc Patience) to Andrew (Head of Department, cc Claudel + Patience), going fully silent after the third tier for as long as the incident stays open, and resetting cleanly the moment a transaction succeeds again.

The engine itself is not ingestion-specific. The next threshold-based alert (a compliance deviation rate, a delivery-failure rate) reuses the same tracker schema and scheduling/locking machinery; the next channel (WhatsApp, SMS) reuses the same dispatch contract.

## Why this service, on this branch

This deployment's `demo-rw` branch of `cce-intelligence-service` is a minimal, fresh build — it does not carry the clinical FHIR/webhook delivery pipeline that exists on the `demo` branch (Kafka-triggered `CommunicationRequest`/`Task`/`ServiceRequest` delivery to EMR "Receiver Adaptors"). That pipeline is a different concern: clinical, per-patient, triggered by `compliance-service`'s protocol-deviation events, delivered to clinical systems. This engine is operational: infrastructure health, triggered by a data-freshness check, delivered to human ops staff by email. The two don't share code on this branch, and nothing here assumes the other pipeline exists.

## Why Postgres, and only Postgres

No ClickHouse dependency, no second datasource. This service connects to the same shared Postgres instance (`ccedb`) that `collector-service`, `compliance-service`, and `scheduler-service` already use — confirmed by their independent Flyway history tables (`flyway_schema_history_collector`, `_compliance`, `_scheduler`) all living in one database. Two things live on that one connection:

1. **Detection** — a read-only query against `collector-service`'s own `inbound_event_log` table: `SELECT MAX(received_at)`. That table is explicitly set up for CDC (`ALTER TABLE inbound_event_log REPLICA IDENTITY FULL`) — it's the authoritative, zero-lag source; a ClickHouse analytics copy of the same table would introduce a dependency on a separate CDC pipeline being healthy, which is itself a real, separately-monitored failure mode elsewhere in this platform. `cce-scheduler-service` already reads another service's table this same way in production (`compliance-service`'s `step_instance`), so this isn't a new kind of coupling for this ecosystem, even though it is new for this specific branch.
2. **State** — `notification_tracker`, this service's own table, tracking exactly which tier of which incident has been sent (data-dictionary.md).

## Core abstractions

- **`AlertEvaluator`** — the contract an alert type implements: decide whether a condition is currently breached and compute a deterministic `reference_key` identifying the occurrence. Only decides *tier 1's* crossing (measured from the incident's true start) — it has no tracker access, so it can't know when tier 1 actually sent, which is what tiers 2+ are measured from instead. `IngestionGapEvaluator` is the first implementation.
- **`AlertTierConfig`** — externalized in `application.yml`, not hardcoded: an ordered list of tiers per alert type, each with a threshold, recipients, one channel, and a template name. Tier 1's threshold is "minutes since the incident began"; every tier after it is "minutes since tier 1 was sent" — matching the PRD's own wording ("24 hours after this alert"), not three independent absolute checkpoints. See flow-diagrams.md.
- **`NotificationTracker`** — one row per incident occurrence, keyed by `(alert_type, reference_key)`. Owns `current_tier` (an ordinal pointer into that alert type's configured tier list, not a fixed scale), `opened_at` (tier 1's send time — the anchor every later tier is measured from), and `status` (`ACTIVE`/`RESOLVED`).
- **`AlertEscalationEngine`** — the scheduled tick: runs every registered evaluator, and — because it's the one place that holds the tracker row — is also where the tier 2+ timing decision actually happens (the evaluator can't make it). Claims and locks the relevant tracker row, dispatches only if a tier's threshold has newly been reached, and closes the tracker the moment the underlying signal recovers.
- **`NotificationDispatcher`** — the send contract. `EmailDispatcher` (Spring Mail + Thymeleaf) is the first implementation; a future `WhatsAppDispatcher`/`SmsDispatcher` implements the same interface with zero change to the engine.

## Concurrency: row-level locking, not leader election

This service can run more than one replica (see deployment-guide.md's HPA note). Every pod runs its own independent scheduled tick — there is no leader gate deciding who's allowed to run. Safety instead comes from an ordinary Postgres row lock on the one thing that actually needs protecting: the specific tracker row a pod is about to send against (`INSERT ... ON CONFLICT DO NOTHING` then `SELECT ... FOR UPDATE`, held open across the dispatch call, released on commit or rollback). Full mechanics and the reasoning for not using a leader-election/advisory-lock approach instead are in flow-diagrams.md.

## Extensibility

Adding a new alert type is 5 changes, exactly 1 of them code: implement `AlertEvaluator`, register it as a Spring bean, add a tier-config block in `application.yml`, add recipient env vars if needed, add Thymeleaf templates. `notification_tracker` needs no migration — `alert_type` is just a new value in an existing column. See api-reference.md for the exact interface contracts.
