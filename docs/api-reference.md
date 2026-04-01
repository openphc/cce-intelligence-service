# API Reference

> **CCE Intelligence Service** — REST API endpoint reference  
> **Base URL**: `http://localhost:8085` | **Prefix**: `/v1/`  
> **Authentication**: OAuth 2.0 via CCE Gateway (no direct token validation)

---

## Table of Contents

1. [Action Definitions](#1-action-definitions)
2. [Action Runs](#2-action-runs)
3. [Receiver Adaptors](#3-receiver-adaptors)
4. [Actuator Endpoints](#4-actuator-endpoints)
5. [Error Response Format](#5-error-response-format)
6. [DTO Schemas](#6-dto-schemas)

---

## 1. Action Definitions

Action Definitions define **what** to do when an intelligence rule fires — the message template, action type, target, and delivery mode. Referenced by PlanDefinition intelligence rules via `definitionCanonical`.

**Required scope**: `action-definitions:read` (GET), `action-definitions:write` (POST, PUT)

---

### 1.1 Create Action Definition

**`POST /v1/action-definitions`** — Register a new action definition.

**Request Body**

```json
{
  "canonicalUrl": "ActivityDefinition/send-overdue-alert",
  "name": "Send Overdue Alert",
  "actionType": "NOTIFICATION",
  "messageTemplate": {
    "subject": "Overdue Alert: {protocol_canonical}",
    "body": "Patient {patient_id} step {action_id} is {days_overdue} days overdue at facility {facility_id}",
    "variables": ["patient_id", "action_id", "days_overdue", "facility_id", "protocol_canonical"]
  },
  "target": "facility",
  "deliveryMode": "WEBHOOK"
}
```

**Response:** `201 Created`

```json
{
  "id": "a1b2c3d4-0001-4000-a000-000000000001",
  "canonicalUrl": "ActivityDefinition/send-overdue-alert",
  "name": "Send Overdue Alert",
  "actionType": "NOTIFICATION",
  "messageTemplate": {
    "subject": "Overdue Alert: {protocol_canonical}",
    "body": "Patient {patient_id} step {action_id} is {days_overdue} days overdue at facility {facility_id}",
    "variables": ["patient_id", "action_id", "days_overdue", "facility_id", "protocol_canonical"]
  },
  "target": "facility",
  "deliveryMode": "WEBHOOK",
  "status": "ACTIVE",
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T10:00:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `400` | Invalid request body (missing required fields, invalid enum values) |
| `409` | `canonicalUrl` already exists |

---

### 1.2 List Action Definitions

**`GET /v1/action-definitions`** — Retrieve all action definitions.

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | `String` | No | Filter by status: `ACTIVE`, `INACTIVE` |
| `actionType` | `String` | No | Filter by action type: `NOTIFICATION`, `ESCALATION`, `COORDINATION` |
| `target` | `String` | No | Filter by target type |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "a1b2c3d4-0001-4000-a000-000000000001",
      "canonicalUrl": "ActivityDefinition/send-overdue-alert",
      "name": "Send Overdue Alert",
      "actionType": "NOTIFICATION",
      "messageTemplate": {
        "subject": "Overdue Alert: {protocol_canonical}",
        "body": "Patient {patient_id} step {action_id} is {days_overdue} days overdue at facility {facility_id}",
        "variables": ["patient_id", "action_id", "days_overdue", "facility_id", "protocol_canonical"]
      },
      "target": "facility",
      "deliveryMode": "WEBHOOK",
      "status": "ACTIVE",
      "createdAt": "2026-03-25T10:00:00Z",
      "updatedAt": "2026-03-25T10:00:00Z"
    }
  ]
}
```

---

### 1.3 Get Action Definition by ID

**`GET /v1/action-definitions/{id}`** — Retrieve a single action definition.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Action definition ID |

**Response:** `200 OK`

```json
{
  "id": "a1b2c3d4-0001-4000-a000-000000000001",
  "canonicalUrl": "ActivityDefinition/send-overdue-alert",
  "name": "Send Overdue Alert",
  "actionType": "NOTIFICATION",
  "messageTemplate": { ... },
  "target": "facility",
  "deliveryMode": "WEBHOOK",
  "status": "ACTIVE",
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T10:00:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Action definition not found |

---

### 1.4 Update Action Definition

**`PUT /v1/action-definitions/{id}`** — Update an existing action definition.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Action definition ID |

**Request Body**

```json
{
  "name": "Send Overdue Alert (Updated)",
  "actionType": "NOTIFICATION",
  "messageTemplate": {
    "subject": "OVERDUE: {protocol_canonical}",
    "body": "URGENT — Patient {patient_id} step {action_id} is {days_overdue} days overdue",
    "variables": ["patient_id", "action_id", "days_overdue", "protocol_canonical"]
  },
  "target": "facility",
  "deliveryMode": "WEBHOOK",
  "status": "ACTIVE"
}
```

> **Note:** `canonicalUrl` is immutable and cannot be changed after creation.

**Response:** `200 OK`

```json
{
  "id": "a1b2c3d4-0001-4000-a000-000000000001",
  "canonicalUrl": "ActivityDefinition/send-overdue-alert",
  "name": "Send Overdue Alert (Updated)",
  "actionType": "NOTIFICATION",
  "messageTemplate": {
    "subject": "OVERDUE: {protocol_canonical}",
    "body": "URGENT — Patient {patient_id} step {action_id} is {days_overdue} days overdue",
    "variables": ["patient_id", "action_id", "days_overdue", "protocol_canonical"]
  },
  "target": "facility",
  "deliveryMode": "WEBHOOK",
  "status": "ACTIVE",
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T11:30:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `400` | Invalid request body |
| `404` | Action definition not found |

---

## 2. Action Runs

Action Runs track the **execution lifecycle** of fired intelligence rules. Created automatically when the intelligence engine processes a trigger. Exposed read-only with support for manual cancellation.

**Required scope**: `action-runs:read` (GET), `action-runs:write` (POST cancel)

---

### 2.1 List Action Runs

**`GET /v1/action-runs`** — Retrieve action runs with filtering and pagination.

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | `String` | No | Filter by status: `PENDING`, `EXECUTING`, `DELIVERED`, `FAILED`, `CANCELLED` |
| `subject` | `String` | No | Filter by patient UPID |
| `deviationId` | `UUID` | No | Filter by deviation |
| `actionDefinitionId` | `UUID` | No | Filter by action definition |
| `severity` | `String` | No | Filter by severity: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `page` | `int` | No | Page number (0-based, default: `0`) |
| `size` | `int` | No | Page size (default: `20`) |

**Response:** `200 OK` — Paginated `ActionRunDto`

```json
{
  "data": [
    {
      "id": "b2c3d4e5-0001-4000-b000-000000000001",
      "actionDefinitionId": "a1b2c3d4-0001-4000-a000-000000000001",
      "actionDefinitionName": "Send Overdue Alert",
      "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001",
      "receiverAdaptorName": "Kigali South Facility Adaptor",
      "deviationId": "d4e5f6a7-0001-4000-d000-000000000001",
      "intelligenceRuleId": "anc-visit-2-overdue-escalation",
      "status": "DELIVERED",
      "subject": "UPID-RWA-12345",
      "protocolCanonical": "PlanDefinition/anc-high-risk|2.1",
      "actionId": "anc-visit-2",
      "facilityId": "FOSA-KGL-001",
      "severity": "HIGH",
      "attemptCount": 1,
      "createdAt": "2026-03-25T10:05:00Z",
      "updatedAt": "2026-03-25T10:05:02Z",
      "deliveredAt": "2026-03-25T10:05:02Z"
    }
  ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

---

### 2.2 Get Action Run by ID

**`GET /v1/action-runs/{id}`** — Retrieve a single action run with full detail.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Action run ID |

**Response:** `200 OK`

```json
{
  "id": "b2c3d4e5-0001-4000-b000-000000000001",
  "actionDefinitionId": "a1b2c3d4-0001-4000-a000-000000000001",
  "actionDefinitionName": "Send Overdue Alert",
  "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001",
  "receiverAdaptorName": "Kigali South Facility Adaptor",
  "deviationId": "d4e5f6a7-0001-4000-d000-000000000001",
  "intelligenceRuleId": "anc-visit-2-overdue-escalation",
  "status": "DELIVERED",
  "subject": "UPID-RWA-12345",
  "protocolCanonical": "PlanDefinition/anc-high-risk|2.1",
  "actionId": "anc-visit-2",
  "facilityId": "FOSA-KGL-001",
  "severity": "HIGH",
  "renderedPayload": {
    "subject": "Overdue Alert: PlanDefinition/anc-high-risk|2.1",
    "body": "Patient UPID-RWA-12345 step anc-visit-2 is 3 days overdue at facility FOSA-KGL-001"
  },
  "deliveryResult": {
    "httpStatus": 200,
    "responseBody": "{\"status\": \"accepted\"}"
  },
  "attemptCount": 1,
  "createdAt": "2026-03-25T10:05:00Z",
  "updatedAt": "2026-03-25T10:05:02Z",
  "deliveredAt": "2026-03-25T10:05:02Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Action run not found |

---

### 2.3 Get Action Run Audit Trail

**`GET /v1/action-runs/{id}/audit`** — Retrieve the audit log entries for an action run.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Action run ID |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "e5f6a7b8-0001-4000-e000-000000000001",
      "actionRunId": "b2c3d4e5-0001-4000-b000-000000000001",
      "eventType": "CREATED",
      "actor": "system",
      "details": null,
      "timestamp": "2026-03-25T10:05:00Z"
    },
    {
      "id": "e5f6a7b8-0002-4000-e000-000000000002",
      "actionRunId": "b2c3d4e5-0001-4000-b000-000000000001",
      "eventType": "DISPATCHED",
      "actor": "system",
      "details": { "adaptorName": "Kigali South Facility Adaptor", "endpointUrl": "https://kgl-south.example.com/webhook" },
      "timestamp": "2026-03-25T10:05:01Z"
    },
    {
      "id": "e5f6a7b8-0003-4000-e000-000000000003",
      "actionRunId": "b2c3d4e5-0001-4000-b000-000000000001",
      "eventType": "DELIVERED",
      "actor": "system",
      "details": { "httpStatus": 200, "responseBody": "{\"status\": \"accepted\"}" },
      "timestamp": "2026-03-25T10:05:02Z"
    }
  ]
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Action run not found |

---

### 2.4 Cancel Action Run

**`POST /v1/action-runs/{id}/cancel`** — Cancel a pending or failed action run.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Action run ID |

**Pre-conditions:**
- Only action runs with status `PENDING` or `FAILED` can be cancelled.
- `EXECUTING`, `DELIVERED`, and `CANCELLED` runs cannot be cancelled.

**Response:** `200 OK`

```json
{
  "id": "b2c3d4e5-0001-4000-b000-000000000001",
  "status": "CANCELLED",
  "updatedAt": "2026-03-25T11:00:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Action run not found |
| `422` | Action run status does not allow cancellation |

**Side Effects:**
- Creates an `action_audit_log` entry with `event_type = 'CANCELLED'`

---

## 3. Receiver Adaptors

Receiver Adaptors represent external **webhook endpoints** that receive intelligence actions. Each adaptor is associated with a target type for routing.

**Required scope**: `admin` (all operations)

---

### 3.1 Register Receiver Adaptor

**`POST /v1/receiver-adaptors`** — Register a new webhook endpoint.

**Request Body**

```json
{
  "name": "Kigali South Facility Adaptor",
  "endpointUrl": "https://kgl-south.example.com/webhook/intelligence",
  "deliveryMode": "WEBHOOK",
  "targetType": "facility",
  "config": {
    "authHeader": "X-API-Key",
    "authValue": "sk-abc123",
    "timeoutMs": 10000
  }
}
```

**Response:** `201 Created`

```json
{
  "id": "c3d4e5f6-0001-4000-c000-000000000001",
  "name": "Kigali South Facility Adaptor",
  "endpointUrl": "https://kgl-south.example.com/webhook/intelligence",
  "deliveryMode": "WEBHOOK",
  "targetType": "facility",
  "status": "ACTIVE",
  "config": {
    "authHeader": "X-API-Key",
    "authValue": "sk-abc123",
    "timeoutMs": 10000
  },
  "createdAt": "2026-03-20T08:00:00Z",
  "updatedAt": "2026-03-20T08:00:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `400` | Invalid request body (missing required fields, invalid URL) |
| `409` | Adaptor `name` already exists |

---

### 3.2 List Receiver Adaptors

**`GET /v1/receiver-adaptors`** — Retrieve all receiver adaptors.

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | `String` | No | Filter by status: `ACTIVE`, `INACTIVE` |
| `targetType` | `String` | No | Filter by target type |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "c3d4e5f6-0001-4000-c000-000000000001",
      "name": "Kigali South Facility Adaptor",
      "endpointUrl": "https://kgl-south.example.com/webhook/intelligence",
      "deliveryMode": "WEBHOOK",
      "targetType": "facility",
      "status": "ACTIVE",
      "config": { ... },
      "createdAt": "2026-03-20T08:00:00Z",
      "updatedAt": "2026-03-20T08:00:00Z"
    }
  ]
}
```

---

### 3.3 Get Receiver Adaptor by ID

**`GET /v1/receiver-adaptors/{id}`** — Retrieve a single adaptor.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Receiver adaptor ID |

**Response:** `200 OK`

```json
{
  "id": "c3d4e5f6-0001-4000-c000-000000000001",
  "name": "Kigali South Facility Adaptor",
  "endpointUrl": "https://kgl-south.example.com/webhook/intelligence",
  "deliveryMode": "WEBHOOK",
  "targetType": "facility",
  "status": "ACTIVE",
  "config": {
    "authHeader": "X-API-Key",
    "authValue": "sk-abc123",
    "timeoutMs": 10000
  },
  "createdAt": "2026-03-20T08:00:00Z",
  "updatedAt": "2026-03-20T08:00:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Receiver adaptor not found |

---

### 3.4 Update Receiver Adaptor

**`PUT /v1/receiver-adaptors/{id}`** — Update an existing adaptor.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Receiver adaptor ID |

**Request Body**

```json
{
  "name": "Kigali South Facility Adaptor",
  "endpointUrl": "https://kgl-south-v2.example.com/webhook/intelligence",
  "deliveryMode": "WEBHOOK",
  "targetType": "facility",
  "status": "ACTIVE",
  "config": {
    "authHeader": "Authorization",
    "authValue": "Bearer tok-xyz789",
    "timeoutMs": 15000
  }
}
```

**Response:** `200 OK`

```json
{
  "id": "c3d4e5f6-0001-4000-c000-000000000001",
  "name": "Kigali South Facility Adaptor",
  "endpointUrl": "https://kgl-south-v2.example.com/webhook/intelligence",
  "deliveryMode": "WEBHOOK",
  "targetType": "facility",
  "status": "ACTIVE",
  "config": {
    "authHeader": "Authorization",
    "authValue": "Bearer tok-xyz789",
    "timeoutMs": 15000
  },
  "createdAt": "2026-03-20T08:00:00Z",
  "updatedAt": "2026-03-25T14:00:00Z"
}
```

| Error Status | Condition |
|:-------------|:----------|
| `400` | Invalid request body |
| `404` | Receiver adaptor not found |

---

### 3.5 Delete Receiver Adaptor

**`DELETE /v1/receiver-adaptors/{id}`** — Remove a receiver adaptor.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Receiver adaptor ID |

**Pre-conditions:**
- Only adaptors with no `PENDING` or `EXECUTING` action runs can be deleted.
- Adaptors with historical action runs (`DELIVERED`, `FAILED`, `CANCELLED`) can be deleted; the `action_run.receiver_adaptor_id` FK is preserved (soft reference).

**Response:** `204 No Content`

| Error Status | Condition |
|:-------------|:----------|
| `404` | Receiver adaptor not found |
| `422` | Adaptor has active (PENDING/EXECUTING) action runs |

---

## 4. Actuator Endpoints

Standard Spring Boot Actuator endpoints for monitoring and operations.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Application health with component details |
| `GET` | `/actuator/health/readiness` | Kubernetes readiness probe |
| `GET` | `/actuator/health/liveness` | Kubernetes liveness probe |
| `GET` | `/actuator/info` | Build and version metadata |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `GET` | `/actuator/env` | Environment properties (restricted in production) |

### Health Check Detail

**`GET /actuator/health`**

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "kafka": {
      "status": "UP",
      "details": {
        "group": "cce-intelligence-service",
        "topics": ["cce.intelligence.triggers"]
      }
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

---

## 5. Error Response Format

All error responses use a consistent envelope:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Action definition not found: a1b2c3d4-0001-4000-a000-999999999999",
  "path": "/v1/action-definitions/a1b2c3d4-0001-4000-a000-999999999999",
  "timestamp": "2026-03-25T10:30:00Z",
  "fieldErrors": null
}
```

### Validation Error Example (400)

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/v1/action-definitions",
  "timestamp": "2026-03-25T10:30:00Z",
  "fieldErrors": [
    { "field": "canonicalUrl", "message": "must not be blank" },
    { "field": "actionType", "message": "must be one of: NOTIFICATION, ESCALATION, COORDINATION" }
  ]
}
```

### Error Code Reference

| Status | Meaning | Common Cause |
|:-------|:--------|:-------------|
| `400` | Bad Request | Validation failed, malformed JSON |
| `404` | Not Found | Resource ID does not exist |
| `409` | Conflict | Unique constraint violation (canonical URL, adaptor name) |
| `422` | Unprocessable Entity | Business rule violation (invalid state transition) |
| `500` | Internal Server Error | Unexpected exception |

---

## 6. DTO Schemas

### ActionDefinitionDto

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | No | Action definition ID |
| `canonicalUrl` | `String` | No | Canonical reference (e.g., `ActivityDefinition/send-alert`) |
| `name` | `String` | No | Human-readable name |
| `actionType` | `String` | No | `NOTIFICATION`, `ESCALATION`, or `COORDINATION` |
| `messageTemplate` | `Object` | No | Template with placeholders and variable list |
| `target` | `String` | No | Target routing type |
| `deliveryMode` | `String` | No | `WEBHOOK` |
| `status` | `String` | No | `ACTIVE` or `INACTIVE` |
| `createdAt` | `OffsetDateTime` | No | Creation timestamp |
| `updatedAt` | `OffsetDateTime` | No | Last update timestamp |

### ActionRunDto

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | No | Action run ID |
| `actionDefinitionId` | `UUID` | No | FK to action definition |
| `actionDefinitionName` | `String` | No | Denormalized action definition name |
| `receiverAdaptorId` | `UUID` | Yes | FK to receiver adaptor (null if unrouted) |
| `receiverAdaptorName` | `String` | Yes | Denormalized adaptor name |
| `deviationId` | `UUID` | No | Deviation that triggered this run |
| `intelligenceRuleId` | `String` | No | PlanDefinition sub-action ID |
| `status` | `String` | No | `PENDING`, `EXECUTING`, `DELIVERED`, `FAILED`, `CANCELLED` |
| `subject` | `String` | No | Patient UPID |
| `protocolCanonical` | `String` | No | Protocol `url\|version` |
| `actionId` | `String` | No | PlanDefinition step action ID |
| `facilityId` | `String` | Yes | Facility ID from trigger |
| `severity` | `String` | No | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `renderedPayload` | `Object` | No | Rendered message (only in detail view) |
| `deliveryResult` | `Object` | Yes | Delivery response (only in detail view) |
| `attemptCount` | `int` | No | Number of delivery attempts |
| `createdAt` | `OffsetDateTime` | No | Creation timestamp |
| `updatedAt` | `OffsetDateTime` | No | Last status change |
| `deliveredAt` | `OffsetDateTime` | Yes | Delivery timestamp |

### ReceiverAdaptorDto

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | No | Adaptor ID |
| `name` | `String` | No | Unique adaptor name |
| `endpointUrl` | `String` | No | Webhook URL |
| `deliveryMode` | `String` | No | `WEBHOOK` |
| `targetType` | `String` | No | Target routing identifier |
| `status` | `String` | No | `ACTIVE` or `INACTIVE` |
| `config` | `Object` | Yes | Auth and configuration overrides |
| `createdAt` | `OffsetDateTime` | No | Registration timestamp |
| `updatedAt` | `OffsetDateTime` | No | Last update timestamp |

### ActionAuditLogDto

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | No | Audit entry ID |
| `actionRunId` | `UUID` | No | FK to action run |
| `eventType` | `String` | No | `CREATED`, `DISPATCHED`, `DELIVERED`, `FAILED`, `CANCELLED`, `RETRIED` |
| `actor` | `String` | No | Who triggered (`system` or user ID) |
| `details` | `Object` | Yes | Event-specific details |
| `timestamp` | `OffsetDateTime` | No | When the event occurred |
