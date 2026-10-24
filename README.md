# CCE Intelligence Service

The **delivery engine** of the CCE platform. Consumes **self-contained** intelligence trigger events from the Compliance Service via Kafka, builds **FHIR R4-compliant payloads** (`CommunicationRequest` / `Task` / `ServiceRequest` passthrough), resolves routing via **destination-adaptor mappings**, and delivers actions to registered **Receiver Adaptors** via webhook.

> The Compliance Service evaluates *when* and *what* to act on, resolving all metadata into a self-contained trigger event. This service handles *where* (destination-based routing) and *how* (FHIR payload + webhook delivery) — with **zero Compliance table reads** on the hot path.

## Architecture

```
Compliance Service → Kafka → Intelligence Consumer → Intelligence Engine
  → Build FHIR Payload from trigger event (CommunicationRequest / Task / ServiceRequest passthrough)
  → Resolve Destination → Receiver Adaptor (via destination_adaptor_mapping)
  → Webhook Delivery → Receiver Adaptor
```

## Tech Stack

| Concern | Technology |
|---------|------------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.4.x |
| Build | Gradle 8.x |
| Database | PostgreSQL 16+ (shared `ccedb`) |
| Messaging | Apache Kafka 3.7+ (KRaft) |
| HTTP Client | Spring WebClient |
| Observability | Micrometer + Prometheus |

## Quick Start

```bash
# Prerequisites: JDK 21+, Docker (for PostgreSQL + Kafka)
docker compose up -d
./gradlew bootRun
```

Health check: `http://localhost:8085/actuator/health`

## Database

The service owns 4 tables (zero read-only Compliance dependencies at runtime):

| Table | Owner | Purpose |
|-------|-------|--------|
| `intelligence_delivery` | Intelligence | Delivery lifecycle per (intelligence_event × adaptor) |
| `receiver_adaptor` | Intelligence | Registered webhook endpoints |
| `destination_adaptor_mapping` | Intelligence | 1:1 routing map (destination → adaptor) |
| `intelligence_delivery_audit_log` | Intelligence | Audit trail |

## Kafka Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `cce.intelligence.triggers` | Inbound | Trigger events from Compliance Service |
| `cce.intelligence.triggers.dlq` | Outbound | Dead-letter queue for failed processing |

## REST API

All requests arrive via the **CCE Gateway Service** (pre-authenticated).

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/intelligence-deliveries` | List intelligence deliveries (filtered, paginated) |
| `GET` | `/v1/intelligence-deliveries/{id}` | Get intelligence delivery by ID |
| `GET` | `/v1/intelligence-deliveries/{id}/audit` | Get delivery audit trail |
| `POST` | `/v1/intelligence-deliveries/{id}/cancel` | Cancel a intelligence delivery |
| `GET` | `/v1/receiver-adaptors` | List receiver adaptors (optional `?status=` filter) |
| `GET` | `/v1/receiver-adaptors/{id}` | Get receiver adaptor by ID |
| `POST` | `/v1/receiver-adaptors` | Register a receiver adaptor |
| `PUT` | `/v1/receiver-adaptors/{id}` | Update a receiver adaptor |
| `DELETE` | `/v1/receiver-adaptors/{id}` | Delete a receiver adaptor |
| `GET` | `/v1/destination-adaptor-mappings` | List destination-adaptor mappings |
| `GET` | `/v1/destination-adaptor-mappings/{id}` | Get mapping by ID |
| `POST` | `/v1/destination-adaptor-mappings` | Create a destination-adaptor mapping |
| `PUT` | `/v1/destination-adaptor-mappings/{id}` | Update mapping status |
| `DELETE` | `/v1/destination-adaptor-mappings/{id}` | Delete a mapping |

## Project Structure

44 source files across 10 packages. Key components:

- **`engine/IntelligenceEngine`** — Core orchestrator (trigger → payload → route → deliver)
- **`engine/FhirPayloadBuilder`** — Builds FHIR CommunicationRequest or Task; passes through original payload for ServiceRequest actions
- **`engine/DestinationRouter`** — Resolves destination → Receiver Adaptor via `destination_adaptor_mapping`
- **`engine/ActionDispatcher`** — Three-phase transactional webhook delivery
- **`webhook/WebhookDeliveryClient`** — WebClient-based HTTP delivery with retry, HMAC signing, and per-adaptor config

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](docs/architecture-overview.md) | System context, pipeline, FHIR payloads, routing model |
| [Flow Diagrams](docs/flow-diagrams.md) | Mermaid diagrams for all processing flows |
| [API Reference](docs/api-reference.md) | REST API endpoints and request/response schemas |
| [Data Dictionary](docs/data-dictionary.md) | Database schema and entity relationships |
| [Kafka Events](docs/kafka-events.md) | Kafka topic contracts and event schemas |
| [Developer Setup](docs/developer-setup.md) | Local development environment setup |