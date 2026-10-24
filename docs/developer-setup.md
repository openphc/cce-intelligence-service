# Developer Setup & Configuration

## 1. Prerequisites

| Tool | Version | Required | Purpose |
|---|---|---|---|
| **Java JDK** | 21 LTS | Yes | Build and runtime |
| **Gradle** | 8.x | Yes | Build tool (via wrapper) |
| **Docker** | 24+ | Recommended | Run PostgreSQL, Kafka locally |
| **Docker Compose** | 2.x | Recommended | Orchestrate infrastructure |
| **PostgreSQL** | 16+ | Yes | Primary database |
| **Apache Kafka** | 3.x | Yes | Message broker |
| **Git** | 2.x | Yes | Version control |

## 2. Quick Start

### 2.1 Clone & Build

```bash
# Clone the repository
git clone <repository-url>
cd cce-intelligence-service


# Build (skip tests for fast iteration)
./gradlew build -x test

# Build with tests
./gradlew build
```

### 2.2 Start Infrastructure

PostgreSQL, Kafka, and the shared database (`ccedb`) are deployed by the **CCE Collector Service**. All CCE services share the same database.

```bash
# Start shared infrastructure (PostgreSQL on port 5433 + Kafka on port 9092)
cd /path/to/cce-collector-service
docker compose up -d

# Verify shared services are running
docker compose ps
```

> **Note:** The Intelligence Service does **not** read Compliance Service tables at runtime (fat event design). It only needs its own 4 tables.

### 2.3 Run the Application

```bash
# Using Gradle
./gradlew bootRun

# Or using the JAR
java -jar build/libs/cce-intelligence-service-1.0.0.jar

# With custom configuration
DB_HOST=localhost DB_PORT=5433 java -jar build/libs/cce-intelligence-service-1.0.0.jar
```

### 2.4 Verify Health

```bash
# Health check
curl http://localhost:8085/actuator/health

# Expected response
# {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

## 3. Configuration Reference

### 3.1 Environment Variables

All configuration can be overridden via environment variables:

#### Database

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5433` | PostgreSQL port (shared with collector service) |
| `DB_NAME` | `ccedb` | Shared database name (all CCE services) |
| `DB_USERNAME` | `cce_user` | Database username (shared with collector service) |
| `DB_PASSWORD` | `cce_pass` | Database password (shared with collector service) |
| `DB_POOL_SIZE` | `10` | HikariCP max pool size |

#### Kafka

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |

#### Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8085` | Application port |

#### Webhook Delivery

| Variable | Default | Description |
|---|---|---|
| `WEBHOOK_CONNECT_TIMEOUT_MS` | `5000` | WebClient connection timeout |
| `WEBHOOK_READ_TIMEOUT_MS` | `10000` | WebClient read timeout |
| `WEBHOOK_RETRY_ATTEMPTS` | `3` | Maximum delivery retry attempts |
| `WEBHOOK_RETRY_INTERVAL_MS` | `2000` | Fixed delay between retries |

### 3.2 Kafka Consumer Configuration

Configured in `application.yml`:

| Property | Value | Description |
|---|---|---|
| `spring.kafka.consumer.group-id` | `cce-intelligence-service` | Consumer group |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Start from earliest on new group |
| `spring.kafka.consumer.isolation-level` | `read_committed` | Transactional reads only |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Manual acknowledgment |
| `spring.kafka.listener.ack-mode` | `RECORD` | Per-record acknowledgment |
| `spring.kafka.listener.concurrency` | `3` | Parallel consumer threads |

### 3.3 Kafka Topic Reference

| Property | Default Value | Description |
|---|---|---|
| `cce.kafka.topics.intelligence-triggers` | `cce.intelligence.triggers` | Consumed intelligence triggers |
| `cce.kafka.topics.dead-letter` | `cce.intelligence.triggers.dlq` | Failed intelligence trigger dead-letter |

### 3.4 JPA & Hibernate

| Property | Value | Description |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema managed by Flyway; Hibernate only validates |
| `spring.jpa.open-in-view` | `false` | Prevents lazy loading in controllers |
| `hibernate.dialect` | `PostgreSQLDialect` | PostgreSQL-specific SQL generation |
| `hibernate.jdbc.time_zone` | `UTC` | All timestamps in UTC |

### 3.5 Flyway

| Property | Value | Description |
|---|---|---|
| `spring.flyway.enabled` | `true` | Auto-apply migrations on startup |
| `spring.flyway.locations` | `classpath:db/migration` | Migration file location |
| `spring.flyway.baseline-on-migrate` | `true` | Baseline existing DBs on first run |

### 3.6 Observability

| Property | Value | Description |
|---|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Exposed actuator endpoints |
| `management.tracing.sampling.probability` | `1.0` | 100% trace sampling |
| `management.metrics.tags.application` | `cce-intelligence-service` | Common metric tag |

## 4. Project Structure

```
cce-intelligence-service/
├── docs/                               # Documentation
│   ├── architecture-overview.md
│   ├── flow-diagrams.md
│   ├── api-reference.md
│   ├── data-dictionary.md
│   ├── kafka-events.md
│   └── developer-setup.md
├── src/
│   └── main/
│       ├── java/org/openphc/cce/intelligence/
│       │   ├── IntelligenceServiceApplication.java
│       │   ├── config/          # Spring configuration (Kafka, WebClient, Async, Properties, Metrics)
│       │   ├── domain/          # Entities, enums, repositories
│       │   │   ├── entity/      # IntelligenceDelivery, ReceiverAdaptor, DestinationAdaptorMapping, IntelligenceDeliveryAuditLog
│       │   │   ├── enums/       # IntelligenceDeliveryStatus, ActionType, IntelligenceSeverity
│       │   │   └── repository/  # JPA repositories for all entities
│       │   ├── engine/          # Intelligence processing pipeline
│       │   │   ├── IntelligenceEngine.java   # Core orchestrator
│       │   │   ├── FhirPayloadBuilder.java   # Builds FHIR CommunicationRequest / Task; passes through ServiceRequest payload
│       │   │   ├── DestinationRouter.java    # Resolve destination → Receiver Adaptor via destination_adaptor_mapping
│       │   │   └── ActionDispatcher.java     # Deliver to mapped Receiver Adaptor
│       │   ├── kafka/           # Kafka consumer
│       │   │   ├── config/      # Consumer factory, topic bindings
│       │   │   ├── consumer/    # IntelligenceTriggerConsumer
│       │   │   └── model/       # IntelligenceTriggerEvent
│       │   ├── service/         # Business logic (IntelligenceDeliveryService, DestinationAdaptorMappingService,
│       │   │                    #   ReceiverAdaptorService, IntelligenceDeliveryAuditService)
│       │   ├── webhook/         # WebClient-based webhook delivery
│       │   └── web/             # REST controllers, DTOs, exception handler
│       │       ├── controller/  # IntelligenceDeliveryController, DestinationAdaptorMappingController,
│       │       │                #   ReceiverAdaptorController
│       │       ├── dto/         # Request/response DTOs
│       │       └── exception/   # GlobalExceptionHandler
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               └── V1__intelligence_schema.sql
├── Dockerfile                          # Multi-stage Docker build
├── .gitignore
├── build.gradle                        # Gradle build configuration
└── settings.gradle                     # Gradle settings
```

## 5. Database Setup

All CCE services share the same database (`ccedb`) on the PostgreSQL instance deployed by the CCE Collector Service (port `5433`, user `cce_user`). The Intelligence Service owns **4 tables** and does **not** read any Compliance Service tables at runtime (fat event design).

### 5.1 Table Ownership

| Category | Tables |
|---|---|
| **Owned (4)** | `receiver_adaptor`, `destination_adaptor_mapping`, `intelligence_delivery`, `intelligence_delivery_audit_log` |
| **FK reference only** | None — the service is fully self-contained with the fat event design |

### 5.2 No Separate Database Creation Needed

The database is created by the collector service's Docker Compose. The Intelligence Service only runs its Flyway migrations for its 4 owned tables on startup.

### 5.3 No Cross-Service Dependencies

The Intelligence Service is fully self-contained. The fat event design means no Compliance Service tables are referenced — not even as FKs. All metadata needed for routing and delivery is carried in the trigger event.

### 5.4 Flyway Migrations

Migrations are applied automatically on application startup. To run manually:

```bash
# Using Gradle Flyway plugin (if configured)
./gradlew flywayMigrate -Dflyway.url=jdbc:postgresql://localhost:5433/ccedb \
                        -Dflyway.user=cce_user \
                        -Dflyway.password=cce_pass

# Check migration status
./gradlew flywayInfo
```

### 5.5 Current Migrations

| Version | Description | Script |
|---|---|---|
| V1 | Intelligence service schema (`receiver_adaptor`, `destination_adaptor_mapping`, `intelligence_delivery`, `intelligence_delivery_audit_log`) | `V1__intelligence_schema.sql` |

## 6. Docker Build

### 6.1 Build Image

```bash
# Build the Docker image
docker build -t cce-intelligence-service:latest .

# Run the container
docker run -d \
  --name intelligence-service \
  -p 8085:8085 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5433 \
  -e DB_NAME=ccedb \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=cce_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  cce-intelligence-service:latest
```

### 6.2 Dockerfile Overview

```
Stage 1: Build (eclipse-temurin:21-jdk-alpine)
  → Copy build.gradle, settings.gradle, download dependencies
  → Copy source, run ./gradlew build

Stage 2: Runtime (eclipse-temurin:21-jre-alpine)
  → Create non-root user 'cce' (UID 1001)
  → Copy JAR from build stage
  → JVM flags: -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC
  → Healthcheck: wget to /actuator/health every 30s
  → Expose port 8085
```

## 7. Key Build Commands

| Command | Purpose |
|---|---|
| `./gradlew build -x test` | Build without tests |
| `./gradlew build` | Build + run unit tests |
| `./gradlew test` | Run unit tests only |
| `./gradlew integrationTest` | Run integration tests (H2 + Mocks) |
| `./gradlew test jacocoTestReport` | Unit tests + coverage report |
| `./gradlew dependencies` | Show dependency tree |
| `./gradlew bootRun` | Run application via Gradle |

## 8. Testing

### 8.1 Test Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ |
| `spring-kafka-test` | Kafka consumer test utilities |
| `okhttp3:mockwebserver` | Mock webhook endpoints for delivery tests |
| `h2` | In-memory database for integration tests |
| `awaitility` | Async test assertion helpers |

### 8.2 Test Categories

| Category | Location | Infrastructure |
|---|---|---|
| Unit tests | `src/test/java` | Mocked dependencies |
| Integration tests | `src/integrationTest/java` | H2 in-memory DB + @MockBean (no containers) |
| API tests | `src/test/java` | MockMvc |
| Webhook tests | `src/test/java` | OkHttp MockWebServer |

### 8.3 Test Tips

```bash
# Run a specific test class
./gradlew test --tests "org.openphc.cce.intelligence.engine.IntelligenceEngineTest"

# Run tests matching a pattern
./gradlew test --tests "*Dispatcher*"

# Run with debug logging
./gradlew test -Dlogging.level.org.openphc.cce.intelligence=DEBUG
```
