# CCE Intelligence Service

The **CCE Intelligence Service** is the action engine of the CCE platform. It consumes intelligence triggers from the Compliance Service via Kafka, evaluates PlanDefinition-defined intelligence rules using JSONLogic, executes actions (notifications, escalations, coordination) through registered Receiver Adaptors, and tracks the full action execution lifecycle.

> **Note:** This service does **not** handle event ingestion (Collector), protocol matching (Compliance), time-based transitions (Scheduler), analytics (Insights), or authentication/authorization (Gateway).

---

## Architecture

```
Compliance Service ──► Kafka (cce.intelligence.triggers) ──► Intelligence Consumer
                                                                     │
                                                              Intelligence Engine
                                                                     │
                                    ┌────────────────────────────────┼────────────────────────────────┐
                                    ▼                                ▼                                ▼
                             Rule Evaluator                  Action Resolver                  Template Renderer
                            (JSONLogic)                (definitionCanonical)               ({variable} substitution)
                                                                                                     │
                                                                                              Action Dispatcher
                                                                                                     │
                                                                                              Receiver Adaptors
                                                                                             (Webhook HTTP POST)
```

### Core Pipeline

1. **Consume** — `IntelligenceTriggerConsumer` receives deviation events from `cce.intelligence.triggers`
2. **Load Context** — Fetch `StepInstance`, `ProtocolInstance`, `Deviation`, `ProtocolDefinition` from the shared database
3. **Extract Rules** — Parse nested sub-actions from PlanDefinition where `condition.language = text/jsonlogic`
4. **Evaluate** — `RuleEvaluator` applies JSONLogic conditions against a `RuleContext` (stepState, daysOverdue, etc.)
5. **Resolve** — Map `definitionCanonical` to an `ActionDefinition` entity
6. **Render** — Substitute `{variable}` placeholders in message templates with runtime values
7. **Dispatch** — Route rendered action to the matching `ReceiverAdaptor` via webhook POST
8. **Track** — Persist `ActionRun` lifecycle (PENDING → EXECUTING → DELIVERED/FAILED)

---

## Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build Tool | Gradle | 8.x |
| Database | PostgreSQL | 16+ (shared `cce_collector` DB) |
| Message Broker | Apache Kafka | 3.7+ (KRaft mode) |
| DB Migrations | Flyway | Spring Boot managed |
| Expression Engine | Apache Johnzon JsonLogic | 2.0.2 |
| HTTP Client | Spring WebClient | Reactive, non-blocking |
| Observability | Micrometer + Prometheus | Spring Boot managed |
| Testing | JUnit 5, Spring Kafka Test, OkHttp MockWebServer | |

---

## Prerequisites

| Tool | Version | Required |
|------|---------|----------|
| Java JDK | 21 LTS | Yes |
| Gradle | 8.x | Yes (via wrapper) |
| Docker & Docker Compose | 24+ / 2.x | Recommended |
| PostgreSQL | 16+ | Yes |
| Apache Kafka | 3.x | Yes |

---

## Quick Start

### 1. Clone & Build

```bash
git clone <repository-url>
cd cce-intelligence-service

# Build without tests
./gradlew build -x test

# Build with tests
./gradlew build
```

### 2. Start Infrastructure

PostgreSQL and Kafka are deployed by the **CCE Collector Service**. All CCE services share the same database.

```bash
# From the collector service directory
cd /path/to/cce-collector-service
docker compose up -d
```

> Ensure the **Compliance Service** has run its Flyway migrations first — the Intelligence Service reads from compliance-owned tables (`protocol_definition`, `protocol_instance`, `step_instance`, `deviation`).

### 3. Run the Application

```bash
./gradlew bootRun

# Or via JAR
java -jar build/libs/cce-intelligence-service-1.0.0.jar
```

### 4. Verify Health

```bash
curl http://localhost:8085/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

---

## Configuration

All settings can be overridden via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8085` | Application port |
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5433` | PostgreSQL port |
| `DB_NAME` | `cce_collector` | Shared database name |
| `DB_USERNAME` | `cce_user` | Database username |
| `DB_PASSWORD` | `cce_pass` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |
| `WEBHOOK_CONNECT_TIMEOUT_MS` | `10000` | WebClient connection timeout |
| `WEBHOOK_READ_TIMEOUT_MS` | `30000` | WebClient read timeout |
| `WEBHOOK_RETRY_ATTEMPTS` | `3` | Maximum delivery retry attempts |
| `WEBHOOK_RETRY_INTERVAL_MS` | `2000` | Delay between retries |

---

## REST API

**Base URL:** `http://localhost:8085` | **Prefix:** `/v1/`  
**Authentication:** OAuth 2.0 via CCE Gateway (no direct token validation)

| Endpoint | Method | Scope | Description |
|----------|--------|-------|-------------|
| `/v1/action-definitions` | GET | `action-definitions:read` | List action definitions |
| `/v1/action-definitions` | POST | `action-definitions:write` | Create action definition |
| `/v1/action-definitions/{id}` | GET | `action-definitions:read` | Get action definition |
| `/v1/action-definitions/{id}` | PUT | `action-definitions:write` | Update action definition |
| `/v1/action-runs` | GET | `action-runs:read` | List action runs (paginated) |
| `/v1/action-runs/{id}` | GET | `action-runs:read` | Get action run detail |
| `/v1/action-runs/{id}/audit` | GET | `action-runs:read` | Get action run audit trail |
| `/v1/action-runs/{id}/cancel` | POST | `action-runs:write` | Cancel pending/failed run |
| `/v1/receiver-adaptors` | GET | `admin` | List receiver adaptors |
| `/v1/receiver-adaptors` | POST | `admin` | Register receiver adaptor |
| `/v1/receiver-adaptors/{id}` | GET | `admin` | Get receiver adaptor |
| `/v1/receiver-adaptors/{id}` | PUT | `admin` | Update receiver adaptor |

See [docs/api-reference.md](docs/api-reference.md) for full request/response schemas.

---

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `cce.intelligence.triggers` | Inbound | Deviation-driven triggers from Compliance Service |
| `cce.intelligence.triggers.dlq` | DLQ | Dead-letter queue for failed trigger processing |

**Consumer config:** group `cce-intelligence-service`, `RECORD` ack mode, 3 concurrent listeners, `read_committed` isolation.

> **Note:** Compliance Service v1.0.0 does **not** yet publish `IntelligenceTriggerEvent` messages. The Intelligence Service REST API is fully functional, but the Kafka consumer will not receive triggers until the Compliance Service producer is implemented in a future release.

---

## Database

The service owns 4 tables in the shared `cce_collector` PostgreSQL database and reads 4 tables from the Compliance Service:

| Table | Owner | Purpose |
|-------|-------|---------|
| `action_definition` | Intelligence | Registered action templates |
| `action_run` | Intelligence | Execution records per fired rule |
| `receiver_adaptor` | Intelligence | Webhook endpoint registrations |
| `action_audit_log` | Intelligence | Audit trail for action lifecycle |
| `protocol_definition` | Compliance (read-only) | PlanDefinition with intelligence rules |
| `protocol_instance` | Compliance (read-only) | Patient enrollment context |
| `step_instance` | Compliance (read-only) | Step runtime state for rule evaluation |
| `deviation` | Compliance (read-only) | Deviation triggering intelligence |

Schema is managed by Flyway and applied automatically on startup.

---

## Docker

```bash
# Build image
docker build -t cce-intelligence-service:latest .

# Run container
docker run -d \
  --name intelligence-service \
  -p 8085:8085 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5433 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  cce-intelligence-service:latest
```

---

## Testing

| Command | Purpose |
|---------|---------|
| `./gradlew test` | Run unit tests |
| `./gradlew integrationTest` | Run integration tests (EmbeddedKafka + H2) |
| `./gradlew test jacocoTestReport` | Unit tests + coverage report |
| `./gradlew test --tests "*RuleEvaluator*"` | Run specific test class |

---

## Project Structure

```
src/main/java/org/openphc/cce/intelligence/
├── config/          # Spring configuration (Kafka, WebClient, JPA, Observability)
├── domain/
│   ├── entity/      # ActionDefinition, ActionRun, ReceiverAdaptor, ActionAuditLog
│   ├── enums/       # ActionRunStatus, ActionType, DeliveryMode, IntelligenceSeverity
│   └── repository/  # JPA repositories (+ read-only for Compliance tables)
├── engine/          # IntelligenceEngine, RuleEvaluator, RuleContext, IntelligenceRule
├── action/          # ActionDefinitionResolver, TemplateRenderer, ActionDispatcher, WebhookDeliveryClient
├── kafka/           # IntelligenceTriggerConsumer, IntelligenceTriggerEvent
├── service/         # ActionDefinitionService, ActionRunService, ReceiverAdaptorService, ActionAuditService
└── web/             # REST controllers, DTOs, GlobalExceptionHandler
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/architecture-overview.md](docs/architecture-overview.md) | System context, pipeline design, state machines, observability |
| [docs/api-reference.md](docs/api-reference.md) | Full REST API endpoint reference with schemas |
| [docs/data-dictionary.md](docs/data-dictionary.md) | Complete database schema, JSONB structures, enums |
| [docs/developer-setup.md](docs/developer-setup.md) | Prerequisites, configuration reference, Docker build |
| [docs/flow-diagrams.md](docs/flow-diagrams.md) | Mermaid diagrams for all processing flows |
| [docs/kafka-events.md](docs/kafka-events.md) | Kafka topic reference, message schemas, consumer config |