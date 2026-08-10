# cce-intelligence-service

This branch (`demo-rw`) hosts a minimal, standalone build of `cce-intelligence-service` scoped to one capability: **RI-63, the operational notification engine**. It does not include the clinical FHIR/webhook delivery pipeline present on the `demo` branch — this deployment's `intelligence-service` is, for now, purely the ops-alerting engine described here.

## Docs

- [architecture-overview.md](docs/architecture-overview.md) — what this service is, why it's shaped this way, the core abstractions
- [flow-diagrams.md](docs/flow-diagrams.md) — the escalation flow, incident lifecycle, and concurrency-safety diagrams
- [data-dictionary.md](docs/data-dictionary.md) — every table and column this service reads or owns, and why
- [api-reference.md](docs/api-reference.md) — the extension-point interfaces (how to add a new alert type or channel), plus the observability surface (Actuator, metrics)
- [developer-setup.md](docs/developer-setup.md) — running this locally, running the tests, seeing all 3 escalation tiers fire without waiting real hours
- [deployment-guide.md](docs/deployment-guide.md) — environment variables, infra dependencies, HPA/concurrency implications, what needs adding in `deploy-scripts`

## One-paragraph summary

A generic scheduled evaluator watches whether CCE is still receiving data (`inbound_event_log.received_at`, collector-service's own table, same shared Postgres this service already runs against). A tracker table (`notification_tracker`) owns exactly how far an incident has escalated — never repeating a tier, never sending after the final tier, resetting cleanly on recovery — and a pluggable dispatcher sends the email. RI-63's ingestion-gap alert is the first thing plugged into this; the design exists so the next alert type and the next channel (WhatsApp, SMS) reuse the same engine instead of a rebuild.
