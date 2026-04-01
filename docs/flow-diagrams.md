# Flow Diagrams

> **CCE Intelligence Service** — Visual representation of core processing flows  
> All diagrams use [Mermaid](https://mermaid.js.org/) syntax.

---

## Table of Contents

1. [End-to-End Intelligence Pipeline](#1-end-to-end-intelligence-pipeline)
2. [Trigger Processing Sequence](#2-trigger-processing-sequence)
3. [Intelligence Rule Evaluation](#3-intelligence-rule-evaluation)
4. [Action Dispatch & Webhook Delivery](#4-action-dispatch--webhook-delivery)
5. [Action Run Lifecycle](#5-action-run-lifecycle)
6. [Retry & Error Handling](#6-retry--error-handling)
7. [REST API Flows](#7-rest-api-flows)

---

## 1. End-to-End Intelligence Pipeline

High-level data flow from Compliance Service deviation through to Receiver Adaptor delivery.

```mermaid
flowchart LR
    CS[Compliance Service] -->|IntelligenceTriggerEvent| K[Kafka<br/>cce.intelligence.triggers]
    K -->|consume| IC[Intelligence<br/>Consumer]
    IC --> IE[Intelligence<br/>Engine]
    IE --> RE[Rule<br/>Evaluator]
    RE --> AR[Action<br/>Resolver]
    AR --> TR[Template<br/>Renderer]
    TR --> AD[Action<br/>Dispatcher]
    AD -->|HTTP POST| RA[Receiver<br/>Adaptor]

    subgraph Intelligence Service
        IC
        IE
        RE
        AR
        TR
        AD
    end

    subgraph External
        RA
    end

    IE -.->|read| DB[(PostgreSQL)]
    AD -.->|write| DB
```

---

## 2. Trigger Processing Sequence

Detailed sequence diagram showing the interaction between components when processing an intelligence trigger.

```mermaid
sequenceDiagram
    participant Kafka as Kafka (cce.intelligence.triggers)
    participant Consumer as IntelligenceTriggerConsumer
    participant Engine as IntelligenceEngine
    participant DB as PostgreSQL
    participant Evaluator as RuleEvaluator
    participant Resolver as ActionResolver
    participant Renderer as TemplateRenderer
    participant Dispatcher as ActionDispatcher
    participant Webhook as Receiver Adaptor

    Kafka->>Consumer: IntelligenceTriggerEvent
    Consumer->>Engine: processTrigger(event)

    Note over Engine,DB: Step 1 — Load Context
    Engine->>DB: findById(protocolInstanceId)
    DB-->>Engine: ProtocolInstance
    Engine->>DB: findById(stepInstanceId)
    DB-->>Engine: StepInstance
    Engine->>DB: findById(protocolDefinitionId)
    DB-->>Engine: ProtocolDefinition (with PlanDefinition JSONB)

    Note over Engine,Evaluator: Step 2 — Extract & Evaluate Rules
    Engine->>Engine: extractIntelligenceRules(planDefinition, actionId)
    loop For each intelligence rule
        Engine->>Evaluator: evaluate(rule.condition, ruleContext)
        Evaluator-->>Engine: matched = true/false

        opt matched = true
            Note over Engine,DB: Step 3 — Idempotency Check
            Engine->>DB: existsByDeviationIdAndIntelligenceRuleId(deviationId, ruleId)
            DB-->>Engine: false (not yet processed)

            Note over Engine,Resolver: Step 4 — Resolve Action Definition
            Engine->>Resolver: resolve(rule.definitionCanonical)
            Resolver->>DB: findByCanonicalUrl(canonicalUrl)
            DB-->>Resolver: ActionDefinition
            Resolver-->>Engine: ActionDefinition

            Note over Engine,Renderer: Step 5 — Render Template
            Engine->>Renderer: render(actionDefinition.messageTemplate, ruleContext)
            Renderer-->>Engine: renderedPayload

            Note over Engine,Dispatcher: Step 6 — Create Run & Dispatch
            Engine->>DB: save(ActionRun [PENDING])
            Engine->>Dispatcher: dispatch(actionRun)
            Dispatcher->>DB: findByTargetTypeAndStatus(target, ACTIVE)
            DB-->>Dispatcher: ReceiverAdaptor
            Dispatcher->>DB: update(ActionRun [EXECUTING])
            Dispatcher->>Webhook: HTTP POST (renderedPayload)
            Webhook-->>Dispatcher: 200 OK
            Dispatcher->>DB: update(ActionRun [DELIVERED])
        end
    end

    Engine-->>Consumer: processing complete
    Consumer->>Kafka: acknowledge
```

---

## 3. Intelligence Rule Evaluation

How intelligence rules are extracted from PlanDefinition and evaluated using JSONLogic.

```mermaid
flowchart TD
    A[Load PlanDefinition from protocol_definition.definition] --> B[Find action matching trigger's actionId]
    B --> C[Extract nested sub-actions<br/>where condition.language = text/jsonlogic]
    C --> D{Sub-actions found?}
    D -->|No| Z[Skip — no intelligence rules for this step]
    D -->|Yes| E[Build RuleContext]

    E --> F[For each sub-action / intelligence rule]
    F --> G[Parse JSONLogic condition expression]
    G --> H[Apply JSONLogic with RuleContext variables]
    H --> I{Condition evaluates to true?}
    I -->|No| J[Log: rule skipped]
    I -->|Yes| K[Check idempotency:<br/>deviation_id + rule_id exists?]
    K --> L{Already processed?}
    L -->|Yes| M[Log: duplicate, skip]
    L -->|No| N[Proceed to Action Resolution]

    J --> F
    M --> F
    N --> O[Done evaluating rules]

    subgraph RuleContext Variables
        direction LR
        R1[stepState]
        R2[deviationType]
        R3[daysOverdue]
        R4[daysPastMissedDate]
        R5[requiredBehavior]
        R6[severity extension]
    end

    E -.-> R1 & R2 & R3 & R4 & R5 & R6
```

### RuleContext Variable Computation

```mermaid
flowchart LR
    SI[StepInstance] -->|state| stepState
    SI -->|overdue_date, now| daysOverdue["daysOverdue<br/>= daysBetween(overdue_date, now)"]
    SI -->|missed_date, now| daysPastMissedDate["daysPastMissedDate<br/>= daysBetween(missed_date, now)"]
    SI -->|required_behavior| requiredBehavior
    D[Deviation] -->|deviation_type| deviationType
    PD[PlanDefinition<br/>sub-action extension] -->|intelligence-severity| severity
```

---

## 4. Action Dispatch & Webhook Delivery

How the Intelligence Service routes an action to the correct Receiver Adaptor and delivers via webhook.

```mermaid
sequenceDiagram
    participant Dispatcher as ActionDispatcher
    participant DB as PostgreSQL
    participant Adaptor as Receiver Adaptor (Webhook)
    participant Audit as AuditService

    Dispatcher->>DB: findReceiverAdaptors(target=actionDef.target, status=ACTIVE)
    DB-->>Dispatcher: List<ReceiverAdaptor>

    alt No active adaptor found
        Dispatcher->>DB: update ActionRun status=FAILED, error="No active adaptor"
        Dispatcher->>Audit: log(FAILED, "No active receiver adaptor for target")
    else Adaptor found
        Dispatcher->>DB: update ActionRun status=EXECUTING, receiver_adaptor_id=adaptor.id
        Dispatcher->>Audit: log(DISPATCHED, adaptorName, endpointUrl)

        Dispatcher->>Adaptor: HTTP POST endpoint_url
        Note right of Adaptor: Headers:<br/>Content-Type: application/json<br/>X-CCE-Action-Run-Id: {runId}<br/>X-CCE-Correlation-Id: {correlationId}<br/>+ adaptor auth headers

        alt HTTP 2xx
            Adaptor-->>Dispatcher: 200 OK
            Dispatcher->>DB: update ActionRun status=DELIVERED, delivered_at=now()
            Dispatcher->>Audit: log(DELIVERED, httpStatus=200)
        else HTTP 4xx (non-retryable)
            Adaptor-->>Dispatcher: 400/401/403/404
            Dispatcher->>DB: update ActionRun status=FAILED
            Dispatcher->>Audit: log(FAILED, httpStatus, "non-retryable")
        else HTTP 5xx / timeout (retryable)
            Adaptor-->>Dispatcher: 500/503/timeout
            Dispatcher->>Dispatcher: retry (up to max attempts)
            Note over Dispatcher: See Retry Flow below
        end
    end
```

### Webhook Payload

```mermaid
flowchart LR
    AD[ActionDefinition<br/>.messageTemplate] --> TR[TemplateRenderer]
    RC[RuleContext<br/>variables] --> TR
    TR --> P[Rendered Payload JSON]
    P --> H[HTTP POST Body]

    subgraph "HTTP POST to Receiver Adaptor"
        H
        Headers["Headers:<br/>Content-Type: application/json<br/>X-CCE-Action-Run-Id<br/>X-CCE-Correlation-Id<br/>+ adaptor.config auth"]
    end
```

---

## 5. Action Run Lifecycle

State machine for `action_run.status` — all valid transitions.

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
        ActionRun created,
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

    B -->|2xx| C[✓ DELIVERED]
    B -->|4xx| D[✗ FAILED<br/>Non-retryable]
    B -->|5xx / Timeout| E{attempt < maxRetries?}

    E -->|Yes| F[Wait retryIntervalMs<br/>default: 2000ms]
    F --> G[Increment attempt_count]
    G --> H[Log: RETRIED audit event]
    H --> A

    E -->|No| I[✗ FAILED<br/>Max retries exceeded]

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

    D -->|Permanent error<br/>missing data, bad rule| I[Log error + skip]
    I --> E

    C --> J[Alert ops:<br/>cce.intelligence.consumer.errors++]
    H --> J
```

---

## 7. REST API Flows

### 7.1 Create Action Definition

```mermaid
sequenceDiagram
    participant Client
    participant Controller as ActionDefinitionController
    participant Service as ActionDefinitionService
    participant DB as PostgreSQL

    Client->>Controller: POST /v1/action-definitions
    Controller->>Controller: Validate request body
    alt Validation failed
        Controller-->>Client: 400 Bad Request
    end

    Controller->>Service: create(dto)
    Service->>DB: existsByCanonicalUrl(canonicalUrl)
    alt Already exists
        Service-->>Controller: ConflictException
        Controller-->>Client: 409 Conflict
    end

    Service->>DB: save(ActionDefinition)
    DB-->>Service: saved entity
    Service-->>Controller: ActionDefinitionDto
    Controller-->>Client: 201 Created
```

### 7.2 Cancel Action Run

```mermaid
sequenceDiagram
    participant Client
    participant Controller as ActionRunController
    participant Service as ActionRunService
    participant DB as PostgreSQL
    participant Audit as AuditService

    Client->>Controller: POST /v1/action-runs/{id}/cancel
    Controller->>Service: cancel(id)
    Service->>DB: findById(id)
    alt Not found
        Service-->>Controller: NotFoundException
        Controller-->>Client: 404 Not Found
    end

    Service->>Service: validate status ∈ {PENDING, FAILED}
    alt Invalid status
        Service-->>Controller: UnprocessableEntityException
        Controller-->>Client: 422 Unprocessable Entity
    end

    Service->>DB: update status=CANCELLED
    Service->>Audit: log(CANCELLED, actor=requestUser)
    Service-->>Controller: ActionRunDto
    Controller-->>Client: 200 OK
```
