# Release Notes — v1.0.0

> **CCE Intelligence Service**  
> **Release Date**: 2026-04-30  
> **Branch**: `release-1.0.0`

---

## Overview

Initial release of the CCE Intelligence Service — the delivery engine of the CCE platform. Consumes self-contained intelligence trigger events from the Compliance Service via Kafka, builds FHIR R4-compliant payloads, resolves routing via destination-adaptor mappings, and delivers actions to registered Receiver Adaptors via webhook.

---

## Features

### Core Pipeline
- **Intelligence trigger consumption** from `cce.intelligence.triggers` Kafka topic
- **FHIR R4 payload generation** — `CommunicationRequest` (notifications/escalations) and `Task` (coordination actions)
- **Destination-based routing** — 1:1 mapping from destination to Receiver Adaptor via `destination_adaptor_mapping`
- **Webhook delivery** — dispatch to the mapped adaptor per trigger event
- **Three-phase transactional dispatch** — PENDING → EXECUTING → DELIVERED/FAILED with audit trail
- **Idempotency** — `(intelligence_event_id, destination_adaptor_mapping_id)` unique constraint prevents duplicate processing

### Webhook Delivery
- WebClient-based non-blocking HTTP POST
- Configurable retry with fixed backoff (default: 3 attempts, 2s interval)
- Per-adaptor config overrides (timeout, retry, custom headers)
- HMAC-SHA256 request signing (`X-CCE-Signature-256`) when `webhookSecret` configured
- Non-retryable (4xx) vs retryable (5xx/timeout) error classification

### REST API
- `GET /v1/intelligence-deliveries` — List with multi-field filtering and pagination
- `GET /v1/intelligence-deliveries/{id}` — Get by ID
- `GET /v1/intelligence-deliveries/{id}/audit` — Delivery audit trail
- `POST /v1/intelligence-deliveries/{id}/cancel` — Cancel PENDING/FAILED deliveries
- `CRUD /v1/receiver-adaptors` — Receiver Adaptor management
- `CRUD /v1/destination-adaptor-mappings` — Destination Adaptor Mapping management
- Consistent `ApiResponse<T>` envelope with `data` + `pagination` fields
- Global exception handling (404, 400, 409, 422)

### Observability
- Micrometer + Prometheus metrics (triggers received, deliveries dispatched/delivered/failed, webhook duration, active destinations)
- MDC-based structured logging with `correlationId`, `intelligenceEventId`, `subject`
- Spring Boot Actuator health checks (db, kafka, diskSpace)

### Database
- 4 owned tables: `receiver_adaptor`, `destination_adaptor_mapping`, `intelligence_delivery`, `intelligence_delivery_audit_log`
- Flyway migrations (V1 schema)
- Shared `ccedb` database — zero Compliance table reads at runtime (fat event design)
- JsonNode for all JSONB columns

---

## Technical Stack

| Component | Version |
|-----------|---------|
| Java | 21 (LTS) |
| Spring Boot | 3.4.5 |
| Gradle | 8.12 |
| PostgreSQL | 16+ |
| Apache Kafka | 3.7+ (KRaft) |
| Spring WebClient | (Boot managed) |
| Flyway | (Boot managed) |
| Micrometer + Prometheus | (Boot managed) |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5433` | PostgreSQL port |
| `DB_NAME` | `ccedb` | Database name |
| `DB_USERNAME` | `cce_user` | Database user |
| `DB_PASSWORD` | `cce_pass` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `SERVER_PORT` | `8085` | HTTP port |
| `WEBHOOK_CONNECT_TIMEOUT_MS` | `5000` | Webhook connect timeout |
| `WEBHOOK_READ_TIMEOUT_MS` | `10000` | Webhook read timeout |
| `WEBHOOK_RETRY_ATTEMPTS` | `3` | Max retries |
| `WEBHOOK_RETRY_INTERVAL_MS` | `2000` | Retry delay |

---

## Deployment

- **Docker**: Multi-stage build with `eclipse-temurin:21-jre-alpine`, non-root user (UID 1001)
- **Port**: 8085
- **Health**: `GET /actuator/health`
- **Metrics**: `GET /actuator/prometheus`

See [Deployment Guide](docs/deployment-guide.md) for full Kubernetes manifests and production configuration.

---

## Dependencies

| Service | Requirement |
|---------|-------------|
| CCE Compliance Service | Must start first (creates `protocol_definition` table referenced by FK) |
| CCE Gateway Service | Routes authenticated requests to this service |
| PostgreSQL | Shared `ccedb` database on port 5433 |
| Apache Kafka | Topic `cce.intelligence.triggers` with 25 partitions |

---

## Known Limitations

- No built-in rate limiting for webhook delivery (relies on per-adaptor timeout configuration)
- No webhook delivery retry scheduler — retries are synchronous within the dispatch phase
- Credential storage (`receiver_adaptor.config.authValue`) is plaintext in database — encrypt at rest in production
- No multi-tenancy support in v1.0.0

---

## Migration Notes

This is the initial release — no migration from previous versions required.

**Startup order:** Ensure the CCE Compliance Service has completed its Flyway migrations (specifically the `protocol_definition` table) before starting this service.
