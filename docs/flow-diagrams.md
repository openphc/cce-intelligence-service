# Flow Diagrams

> **CCE Intelligence Service** — Visual representation of core processing flows  
> All diagrams use [Mermaid](https://mermaid.js.org/) syntax.

---

## Table of Contents

1. [End-to-End Intelligence Pipeline](#1-end-to-end-intelligence-pipeline)
2. [Trigger Processing Sequence](#2-trigger-processing-sequence)
3. [Destination Routing](#3-destination-routing)
4. [Action Dispatch & Webhook Delivery](#4-action-dispatch--webhook-delivery)
5. [Intelligence Delivery Lifecycle](#5-delivery-run-lifecycle)
6. [Retry & Error Handling](#6-retry--error-handling)
7. [REST API Flows](#7-rest-api-flows)

---

## 1. End-to-End Intelligence Pipeline

High-level data flow from Compliance Service trigger through to Receiver Adaptor delivery. The Compliance Service handles all condition evaluation and publishes a **self-contained** trigger event; this service is purely a routing and delivery engine with **zero Compliance table reads** on the hot path.

```mermaid
flowchart LR
    CS[Compliance Service] -->|IntelligenceTriggerEvent<br/>self-contained fat event| K[Kafka<br/>cce.intelligence.triggers]
    K -->|consume| IC[Intelligence<br/>Consumer]
    IC --> IE[Intelligence<br/>Engine]
    IE --> DR[Destination<br/>Router]
    DR --> FB[FHIR Payload<br/>Builder]
    FB --> AD[Action<br/>Dispatcher]
    AD -->|HTTP POST| RA1[Receiver<br/>Adaptor]

    subgraph Intelligence Service
        IC
        IE
        FB
        DR
        AD
    end

    subgraph External
        RA1
    end

    DR -.->|read destination_adaptor_mapping| DB[(PostgreSQL)]
    AD -.->|write intelligence_delivery| DB
```

---

## 2. Trigger Processing Sequence

Detailed sequence diagram showing the interaction between components when processing an intelligence trigger. The trigger event is self-contained — no Compliance table lookups needed.

```mermaid
sequenceDiagram
    participant Kafka as Kafka (cce.intelligence.triggers)
    participant Consumer as IntelligenceTriggerConsumer
    participant Engine as IntelligenceEngine
    participant DB as PostgreSQL
    participant Builder as FhirPayloadBuilder
    participant Router as DestinationRouter
    participant Dispatcher as ActionDispatcher
    participant Webhook as Receiver Adaptor

    Kafka->>Consumer: IntelligenceTriggerEvent (fat event)
    Consumer->>Engine: processTrigger(event)

    Note over Engine,DB: Step 1 — Idempotency Check
    Engine->>DB: findExistingDelivery(intelligenceEventId, mappingId)
    DB-->>Engine: existing delivery or null

    alt Already delivered
        Engine-->>Consumer: return early (no-op)
    end

    Note over Engine,Router: Step 2 — Resolve Destination
    Engine->>Router: resolveAdaptor(intelligenceDestination)
    Router->>DB: query destination_adaptor_mapping<br/>WHERE destination = :destination<br/>AND status = 'ACTIVE'
    DB-->>Router: DestinationAdaptorMapping + ReceiverAdaptor
    Router-->>Engine: ReceiverAdaptor (or null if no mapping)

    alt No mapping found
        Engine->>DB: save(IntelligenceDelivery [FAILED] - no active mapping)
        Engine-->>Consumer: processing complete
    end

    Note over Engine,Dispatcher: Step 3 — Deliver
    Engine->>DB: save(IntelligenceDelivery [PENDING])
    Engine->>Builder: build(triggerEvent, intelligenceDeliveryId)
    Builder-->>Engine: FHIR CommunicationRequest / Task / ServiceRequest passthrough
    Engine->>Dispatcher: dispatch(intelligenceDelivery, fhirPayload, adaptor)
    Dispatcher->>DB: update(IntelligenceDelivery [EXECUTING])
    Dispatcher->>Webhook: HTTP POST (FHIR payload)
    Webhook-->>Dispatcher: 200 OK
    Dispatcher->>DB: update(IntelligenceDelivery [DELIVERED])

    Engine-->>Consumer: processing complete
    Consumer->>Kafka: acknowledge
```

---

## 3. Destination Routing

How the Intelligence Service resolves which Receiver Adaptor should receive a delivery for a given intelligence destination. Each destination maps to exactly one adaptor (1:1 mapping).

```mermaid
flowchart TD
    ACTION["Trigger received<br/>intelligenceDestination = 'supervisor'"] --> QUERY["Query destination_adaptor_mapping<br/>WHERE destination = 'supervisor'<br/>AND status = 'ACTIVE'"]
    QUERY --> JOIN["JOIN receiver_adaptor<br/>WHERE status = 'ACTIVE'"]
    JOIN --> RESULT{Mapping found?}

    RESULT -->|No| FAIL["Create IntelligenceDelivery<br/>status = FAILED<br/>error = 'No active mapping for destination'"]
    RESULT -->|Yes| DELIVER["Create IntelligenceDelivery<br/>→ Build FHIR Payload<br/>→ Dispatch to Receiver Adaptor"]

    DELIVER --> DISPATCH["Dispatch webhook"]
```

### Destination Routing Example

```mermaid
flowchart TD
    subgraph "Trigger Events (from Compliance Service)"
        T1["intelligenceDestination: supervisor"]
        T2["intelligenceDestination: patient-reminder"]
        T3["intelligenceDestination: lab-coordinator"]
    end

    subgraph "Destination Adaptor Mappings"
        M1["supervisor → CHW Lead SMS Gateway"]
        M2["patient-reminder → WhatsApp Bot Adaptor"]
        M3["lab-coordinator → Lab Coordinator Dashboard"]
    end

    subgraph "Receiver Adaptors"
        A1["CHW Lead SMS Gateway"]
        A2["WhatsApp Bot Adaptor"]
        A3["Lab Coordinator Dashboard"]
    end

    T1 --> M1 --> A1
    T2 --> M2 --> A2
    T3 --> M3 --> A3
```

---

## 4. Action Dispatch & Webhook Delivery

How the Intelligence Service delivers an action to each subscribed Receiver Adaptor via webhook.

```mermaid
sequenceDiagram
    participant Dispatcher as ActionDispatcher
    participant DB as PostgreSQL
    participant Adaptor as Receiver Adaptor (Webhook)
    participant Audit as IntelligenceDeliveryAuditService

    Dispatcher->>DB: update IntelligenceDelivery status=EXECUTING
    Dispatcher->>Audit: log(DISPATCHED, adaptorName, definition.address)

    Dispatcher->>Adaptor: HTTP POST definition.address
    Note right of Adaptor: Headers:<br/>Content-Type: application/fhir+json<br/>X-CCE-Intelligence-Delivery-Id: {runId}<br/>X-CCE-Intelligence-Event-Id: {intelligenceEventId}<br/>X-CCE-Signature-256: HMAC-SHA256 (if configured)<br/>+ adaptor auth headers from config

    alt HTTP 2xx
        Adaptor-->>Dispatcher: 200 OK
        Dispatcher->>DB: update IntelligenceDelivery status=DELIVERED, delivered_at=now()
        Dispatcher->>Audit: log(DELIVERED, httpStatus=200)
    else HTTP 4xx (non-retryable)
        Adaptor-->>Dispatcher: 400/401/403/404
        Dispatcher->>DB: update IntelligenceDelivery status=FAILED
        Dispatcher->>Audit: log(FAILED, httpStatus, "non-retryable")
    else HTTP 5xx / timeout (retryable)
        Adaptor-->>Dispatcher: 500/503/timeout
        Dispatcher->>Dispatcher: retry (see Retry Flow)
    end
```

### Webhook Payload — FHIR Resource Generation

```mermaid
flowchart LR
    TE["TriggerEvent fields<br/>actionType, severity, intelligenceDestination,<br/>subject, eventPayload, etc."] --> FB[FhirPayloadBuilder]
    DRI["IntelligenceDelivery ID"] --> FB
    AT{"ActionType?"} --> FB
    FB -->|CommunicationRequest| CR["FHIR CommunicationRequest"]
    FB -->|Task| TK["FHIR Task"]
    FB -->|"ServiceRequest + eventPayload"| PT["Passthrough:<br/>Original eventPayload as-is"]
    FB -->|"ServiceRequest (no eventPayload)"| SR["FHIR ServiceRequest (built)"]
    CR --> H[HTTP POST Body]
    TK --> H
    PT --> H
    SR --> H

    subgraph "HTTP POST to Receiver Adaptor"
        H
        Headers["Headers:<br/>Content-Type: application/fhir+json<br/>X-CCE-Intelligence-Delivery-Id<br/>X-CCE-Intelligence-Event-Id<br/>X-CCE-Signature-256 (if webhookSecret configured)<br/>+ adaptor.config authHeader + customHeaders"]
    end
```

---

## 5. Intelligence Delivery Lifecycle

State machine for `intelligence_delivery.status` — all valid transitions.

```mermaid
stateDiagram-v2
    [*] --> PENDING : Created by engine

    PENDING --> EXECUTING : Dispatched to adaptor
    PENDING --> CANCELLED : Cancelled via API

    EXECUTING --> DELIVERED : HTTP 2xx received
    EXECUTING --> FAILED : Max retries exceeded or non-retryable error

    FAILED --> CANCELLED : Cancelled via API

    DELIVERED --> [*]
    CANCELLED --> [*]

    note right of PENDING
        IntelligenceDelivery created,
        awaiting dispatch
    end note

    note right of EXECUTING
        Webhook call in progress
        (possibly retrying)
    end note

    note right of DELIVERED
        Terminal state.
        Successfully delivered.
    end note

    note right of FAILED
        Terminal state.
        Can be cancelled.
    end note
```

---

## 6. Retry & Error Handling

### 6.1 Webhook Delivery Retry

```mermaid
flowchart TD
    A[HTTP POST to Receiver Adaptor] --> B{Response?}

    B -->|2xx| C[DELIVERED]
    B -->|4xx| D[FAILED<br/>Non-retryable]
    B -->|5xx / Timeout| E{attempt < maxRetries?}

    E -->|Yes| F[Wait retryIntervalMs<br/>default: 2000ms]
    F --> G[Increment attempt_count]
    G --> H[Log: RETRIED audit event]
    H --> A

    E -->|No| I[FAILED<br/>Max retries exceeded]

    subgraph Retry Config
        direction LR
        RC1["maxRetries = 3"]
        RC2["retryInterval = 2000ms"]
        RC3["retryable: 5xx, timeout"]
        RC4["non-retryable: 4xx"]
    end
```

### 6.2 Kafka Consumer Error Handling

```mermaid
flowchart TD
    A[Receive IntelligenceTriggerEvent] --> B{Deserialize OK?}

    B -->|No| C["Send to DLQ<br/>cce.intelligence.triggers.dlq"]
    B -->|Yes| D{Process trigger}

    D -->|Success| E[Acknowledge record]
    D -->|Transient error| F{Retry count < 3?}
    F -->|Yes| G[Retry with backoff]
    G --> D
    F -->|No| H["Send to DLQ<br/>cce.intelligence.triggers.dlq"]

    C --> J[Alert ops:<br/>cce.intelligence.consumer.errors++]
    H --> J
```

---

## 7. REST API Flows

### 7.1 Create Destination Adaptor Mapping

```mermaid
sequenceDiagram
    participant Client
    participant Controller as DestinationAdaptorMappingController
    participant Service as DestinationAdaptorMappingService
    participant DB as PostgreSQL

    Client->>Controller: POST /v1/destination-adaptor-mappings
    Controller->>Controller: Validate request body
    alt Validation failed
        Controller-->>Client: 400 Bad Request
    end

    Controller->>Service: create(dto)

    Service->>DB: existsById(receiverAdaptorId)
    alt Adaptor not found
        Service-->>Controller: NotFoundException
        Controller-->>Client: 404 Not Found
    end

    Service->>DB: existsByDestination(destination)
    alt Destination already mapped
        Service-->>Controller: ConflictException
        Controller-->>Client: 409 Conflict (destination is unique)
    end

    Service->>DB: save(DestinationAdaptorMapping)
    DB-->>Service: saved entity
    Service-->>Controller: DestinationAdaptorMappingDto
    Controller-->>Client: 201 Created
```

### 7.2 Cancel Intelligence Delivery

```mermaid
sequenceDiagram
    participant Client
    participant Controller as IntelligenceDeliveryController
    participant Service as IntelligenceDeliveryService
    participant DB as PostgreSQL
    participant Audit as IntelligenceDeliveryAuditService

    Client->>Controller: POST /v1/intelligence-deliveries/{id}/cancel
    Controller->>Service: cancel(id)
    Service->>DB: findById(id)
    alt Not found
        Service-->>Controller: NotFoundException
        Controller-->>Client: 404 Not Found
    end

    Service->>Service: validate status in {PENDING, FAILED}
    alt Invalid status
        Service-->>Controller: UnprocessableEntityException
        Controller-->>Client: 422 Unprocessable Entity
    end

    Service->>DB: update status=CANCELLED
    Service->>Audit: log(CANCELLED, actor=requestUser)
    Service-->>Controller: IntelligenceDeliveryDto
    Controller-->>Client: 200 OK
```

### 7.3 Delete Receiver Adaptor

```mermaid
sequenceDiagram
    participant Client
    participant Controller as ReceiverAdaptorController
    participant Service as ReceiverAdaptorService
    participant DB as PostgreSQL

    Client->>Controller: DELETE /v1/receiver-adaptors/{id}
    Controller->>Service: delete(id)
    Service->>DB: findById(id)
    alt Not found
        Service-->>Controller: NotFoundException
        Controller-->>Client: 404 Not Found
    end

    Service->>DB: existsActiveMappings(adaptorId)
    alt Active mappings exist
        Service-->>Controller: UnprocessableEntityException
        Controller-->>Client: 422 — Active destination-adaptor mappings reference this adaptor
    end

    Service->>DB: existsActiveIntelligenceDeliveries(adaptorId)
    alt Active intelligence deliveries exist
        Service-->>Controller: UnprocessableEntityException
        Controller-->>Client: 422 — Active intelligence deliveries reference this adaptor
    end

    Service->>DB: delete(adaptor)
    Controller-->>Client: 204 No Content
```
