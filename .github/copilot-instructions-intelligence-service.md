# CCE Intelligence Service — AI Agent Instructions

## Architecture

Spring Boot 3.4.x / Java 21 microservice that consumes **self-contained intelligence triggers** from the Compliance Service via Kafka, resolves routing via **destination-adaptor mappings** (1:1: destination → adaptor), builds FHIR R4-compliant payloads (`CommunicationRequest` / `Task` / `ServiceRequest` passthrough), and delivers actions to **Receiver Adaptors** via webhook. This is the **routing and delivery engine** of the CCE platform.

> **Naming:** This service is referred to as "Intelligence Engine" or "Intelligence Engine & Action Execution" in the CCE Solution Design v0.3. Implementation name: **Intelligence Service** (`cce-intelligence-service`).

**Core pipeline:** Kafka trigger (fat event) → resolve destination → adaptor mapping (1:1) → idempotency check → build FHIR payload → create IntelligenceDelivery → webhook POST → track outcome.

> **Fat event design:** The Compliance Service resolves **all** metadata at publish time (action type, severity, intelligence destination, protocol definition ID, action definition ID). The Intelligence Service requires **zero Compliance table reads** on the hot path — no `@Immutable` entities, no read-only repositories.

## Key Conventions

- **Package:** `org.openphc.cce.intelligence` — ~44 source files across 10 packages
- **Entities:** 4 owned JPA entities (`IntelligenceDelivery`, `ReceiverAdaptor`, `DestinationAdaptorMapping`, `IntelligenceDeliveryAuditLog`) — **no read-only entities**
- **Enums:** Value-based enums (never ordinals) — `IntelligenceDeliveryStatus`, `ActionType`, `IntelligenceSeverity`
- **DTOs:** Separate DTOs in `web/dto/`, mapped via `DtoMapper` — never expose entities in REST responses
- **All timestamps:** `OffsetDateTime` in UTC (`hibernate.jdbc.time_zone=UTC`)
- **IDs:** `UUID` for all entity primary keys
- **JSONB columns:** Use Jackson `JsonNode` (not `Map<String, Object>`)
- **Kafka:** Consumes `IntelligenceTriggerEvent` from `cce.intelligence.triggers`; does not produce to any topic
- **Schema migrations:** Flyway only (`spring.jpa.hibernate.ddl-auto=validate`) — never let Hibernate modify schema
- **Build tool:** Gradle 8.x
- **Response envelope:** `{ "data": ... }` for success, `{ "error": { "code": "...", "message": "..." } }` for errors

## Critical Design Patterns

- **Self-contained trigger events (fat event):** The Compliance Service publishes `IntelligenceTriggerEvent` with all metadata pre-resolved (`actionType`, `severity`, `intelligenceDestination`, `protocolDefinitionId`, `actionDefinitionId`). The Intelligence Service does **not** read any Compliance tables at runtime — no `action_definition`, `intelligence_event_log`, `protocol_definition`, `protocol_instance`, `step_instance`. This eliminates all cross-service runtime dependencies.
- **Destination-adaptor mapping routing (1:1):** Routing uses a `destination_adaptor_mapping` table mapping `destination` → `ReceiverAdaptor`. The `destination` column is unique — each destination maps to exactly one adaptor. No fan-out, no wildcards.
- **ServiceRequest passthrough:** When `actionType` is `ServiceRequest` and the trigger event includes an `eventPayload` (JsonNode), the `FhirPayloadBuilder` passes the original payload through as-is to the Receiver Adaptor. If `eventPayload` is `null`, a synthetic `ServiceRequest` is built as a fallback.
- **FHIR Endpoint for Receiver Adaptors:** The `receiver_adaptor` table stores a FHIR R4 **Endpoint** resource in the `definition` JSONB column (address, connection type, payload types). The `config` JSONB stores operational concerns (auth headers, webhook secret, retry overrides). This separates FHIR-standard adaptor identity from implementation-specific configuration.
- **Single dispatch (no fan-out):** One intelligence trigger → resolve one destination → one adaptor → one IntelligenceDelivery → one webhook POST.
- **IntelligenceDelivery lifecycle:** `PENDING → EXECUTING → DELIVERED | FAILED | CANCELLED`. Each trigger creates at most one IntelligenceDelivery record.
- **Idempotency:** `(intelligenceEventId, destinationAdaptorMappingId)` compound unique constraint on `intelligence_delivery` prevents duplicate processing. Re-delivered Kafka messages produce no duplicate deliveries.
- **Table naming:** Intelligence Service owned tables use distinct names (`intelligence_delivery`, `intelligence_delivery_audit_log`, `destination_adaptor_mapping`, `receiver_adaptor`) to avoid conflicts with Compliance Service tables in the shared `ccedb` database.

## IntelligenceDelivery State Machine

`PENDING → EXECUTING → DELIVERED` (success path)
`PENDING → EXECUTING → FAILED` (delivery failure after retries)
`PENDING → CANCELLED` (manual cancellation via API)
`FAILED → CANCELLED` (manual cancellation via API)
Terminal states: `DELIVERED`, `CANCELLED`.

## Database Access

### Owned Tables (Read-Write) — 4 tables

| Table | Purpose |
|---|---|
| `receiver_adaptor` | FHIR Endpoint definition (`definition` JSONB) + operational config (`config` JSONB) |
| `destination_adaptor_mapping` | 1:1 routing: destination (unique) → receiver_adaptor |
| `intelligence_delivery` | Delivery lifecycle per (intelligence_event × adaptor); `intelligence_event_id` stored for traceability only |
| `intelligence_delivery_audit_log` | Audit trail for delivery operations |

### No Read-Only Tables

The Intelligence Service does **not** read any Compliance Service tables at runtime. All metadata is carried in the fat trigger event. The `intelligence_event_id` and `action_definition_id` columns on `intelligence_delivery` are stored for traceability and cross-service correlation only — not as runtime FKs.

### Shared Database Model

The Intelligence Service connects to the **same PostgreSQL database** (`ccedb`) as all other CCE services. Infrastructure (PostgreSQL on port 5433, Kafka on port 9092) is deployed by the **CCE Collector Service**. The Intelligence Service's Flyway migration creates its 4 owned tables.

## Kafka Integration

### Topic Consumed

| Topic | Key | Consumer Group | Purpose |
|---|---|---|---|
| `cce.intelligence.triggers` | `intelligenceEventId` | `cce-intelligence-service` | Self-contained intelligence triggers from Compliance Service |
| `cce.intelligence.triggers.dlq` | — | — | Dead letter queue for failed triggers |

### IntelligenceTriggerEvent Schema (Inbound)

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440099",
  "subject": "260225-0002-5501",
  "intelligenceEventId": "990e8400-e29b-41d4-a716-446655440010",
  "actionDefinitionId": "aad00001-0001-0001-0001-000000000001",
  "protocolDefinitionId": "ppd00001-0001-0001-0001-000000000001",
  "actionType": "CommunicationRequest",
  "severity": "HIGH",
  "intelligenceDestination": "supervisor",
  "stepState": "overdue",
  "actionId": "anc-visit-2",
  "protocolCanonical": "http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1",
  "detectedAt": "2026-03-25T00:00:05Z",
  "eventPayload": null
}
```

> **`eventPayload`:** Optional `JsonNode`. When present and `actionType` is `ServiceRequest`, the original FHIR payload from the Compliance Service is passed through to the Receiver Adaptor as-is.

> **Trigger type derivation:** Derived from `stepState` alone: `due` → `step.due`, `overdue` → `deviation.overdue`, `missed` → `deviation.missed`, `completed` → `step.completed`.

## API Endpoints

All endpoints prefixed with `/v1/`. Authentication handled by Gateway.

### Intelligence Deliveries (`intelligence-deliveries:read|write`)

| Method | Path | Description |
|---|---|---|
| GET | `/v1/intelligence-deliveries` | List intelligence deliveries (with filters + pagination) |
| GET | `/v1/intelligence-deliveries/{id}` | Get intelligence delivery by ID (full detail) |
| GET | `/v1/intelligence-deliveries/{id}/audit` | Get audit trail for delivery |
| POST | `/v1/intelligence-deliveries/{id}/cancel` | Cancel a PENDING/FAILED intelligence delivery |

### Destination Adaptor Mappings (`destination-adaptor-mappings:read|write`)

| Method | Path | Description |
|---|---|---|
| POST | `/v1/destination-adaptor-mappings` | Create a destination-adaptor mapping |
| GET | `/v1/destination-adaptor-mappings` | List mappings (filter by destination, adaptorId, status) |
| GET | `/v1/destination-adaptor-mappings/{id}` | Get mapping by ID |
| PUT | `/v1/destination-adaptor-mappings/{id}` | Update mapping (receiverAdaptorId, status) |
| DELETE | `/v1/destination-adaptor-mappings/{id}` | Delete mapping |

### Receiver Adaptors (`admin`)

| Method | Path | Description |
|---|---|---|
| POST | `/v1/receiver-adaptors` | Register a receiver adaptor (FHIR Endpoint definition + config) |
| GET | `/v1/receiver-adaptors` | List registered adaptors |
| GET | `/v1/receiver-adaptors/{id}` | Get adaptor by ID |
| PUT | `/v1/receiver-adaptors/{id}` | Update adaptor (definition + config) |
| DELETE | `/v1/receiver-adaptors/{id}` | Deregister adaptor |

### Response Envelope

```json
{ "data": { ... } }          // Success
{ "error": { "code": "...", "message": "..." } }  // Error
```

## Build & Run

```bash
./gradlew build -x test                # Fast build
./gradlew build                         # Build + all tests
cd /path/to/cce-collector-service && docker compose up -d  # Start shared PostgreSQL + Kafka
./gradlew bootRun                       # Run app (port 8085)
curl localhost:8085/actuator/health     # Health check
```

## Testing

- Unit tests: mocked dependencies — `src/test/java`
- Integration tests: H2 in-memory DB + @MockBean — `src/integrationTest/java`
- API tests: MockMvc
- Webhook tests: OkHttp MockWebServer
- Run unit tests: `./gradlew test`
- Run integration tests: `./gradlew integrationTest`

## Key Files to Read First

- `docs/architecture-overview.md` — core pipeline, destination-adaptor routing (1:1), state machines
- `docs/kafka-events.md` — IntelligenceTriggerEvent schema (fat event, eventPayload), consumer config, DLQ
- `docs/data-dictionary.md` — intelligence_delivery, destination_adaptor_mapping, receiver_adaptor (FHIR Endpoint) schemas
- `docs/api-reference.md` — all REST endpoints with request/response examples
- `docs/developer-setup.md` — local setup, shared database requirement

## What's NOT in Scope (Release 1.0.0)

- **Action Definition CRUD** — owned by Compliance Service; Intelligence Service does not access it at runtime
- **Retry with exponential backoff** — failed webhook deliveries use fixed-interval retry. Exponential backoff deferred.
- **Receiver Adaptor health monitoring** — no circuit breaker on adaptor endpoints in 1.0.0
- **Coordination action tracking** — coordination actions are delivered but completion is tracked by the Compliance Service's normal event matching
- **Batch intelligence processing** — triggers processed individually (no batching optimization)
