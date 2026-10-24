# API Reference

> **CCE Intelligence Service** — REST API endpoint reference  
> **Base URL**: `http://localhost:8085` | **Prefix**: `/v1/`  
> **Authentication**: OAuth 2.0 via CCE Gateway (no direct token validation)

---

## Table of Contents

1. [Intelligence Deliveries](#1-intelligence-deliveries)
2. [Destination Adaptor Mappings](#2-destination-adaptor-mappings)
3. [Receiver Adaptors](#3-receiver-adaptors)
4. [Actuator Endpoints](#4-actuator-endpoints)
5. [Error Response Format](#5-error-response-format)

---

## 1. Intelligence Deliveries

Intelligence Deliveries track the **delivery lifecycle** of fired intelligence actions to Receiver Adaptors. Created automatically when the intelligence engine processes a trigger and resolves the destination to a mapped adaptor. Exposed read-only with support for manual cancellation.

**Required scope**: `intelligence-deliveries:read` (GET), `intelligence-deliveries:write` (POST cancel)

---

### 1.1 List Intelligence Deliveries

**`GET /v1/intelligence-deliveries`** — Retrieve intelligence deliveries with filtering and pagination.

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | `String` | No | Filter by status: `PENDING`, `EXECUTING`, `DELIVERED`, `FAILED`, `CANCELLED` |
| `subject` | `String` | No | Filter by patient UPID |
| `intelligenceEventId` | `UUID` | No | Filter by intelligence event |
| `actionDefinitionId` | `UUID` | No | Filter by action definition |
| `actionType` | `String` | No | Filter by action type: `CommunicationRequest`, `Task`, `ServiceRequest` |
| `severity` | `String` | No | Filter by severity: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `destination` | `String` | No | Filter by destination name (e.g., `supervisor`) |
| `page` | `int` | No | Page number (0-based, default: `0`) |
| `size` | `int` | No | Page size (default: `20`) |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "b2c3d4e5-0001-4000-b000-000000000001",
      "intelligenceEventId": "990e8400-e29b-41d4-a716-446655440010",
      "actionDefinitionId": "a1b2c3d4-0001-4000-a000-000000000001",
      "actionType": "CommunicationRequest",
      "actionId": "anc-visit-2",
      "destination": "supervisor",
      "destinationAdaptorMappingId": "d4e5f6a7-0001-4000-d000-000000000010",
      "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001",
      "receiverAdaptorName": "Kigali South SMS Gateway",
      "status": "DELIVERED",
      "subject": "260225-0002-5501",
      "protocolCanonical": "http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1",
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

### 1.2 Get Intelligence Delivery by ID

**`GET /v1/intelligence-deliveries/{id}`** — Retrieve a single intelligence delivery with full detail including rendered payload and delivery result.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Intelligence delivery ID |

**Response:** `200 OK`

```json
{
  "data": {
    "id": "b2c3d4e5-0001-4000-b000-000000000001",
    "intelligenceEventId": "990e8400-e29b-41d4-a716-446655440010",
    "actionDefinitionId": "a1b2c3d4-0001-4000-a000-000000000001",
    "actionType": "CommunicationRequest",
    "actionId": "anc-visit-2",
    "destination": "supervisor",
    "destinationAdaptorMappingId": "d4e5f6a7-0001-4000-d000-000000000010",
    "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001",
    "receiverAdaptorName": "Kigali South SMS Gateway",
    "status": "DELIVERED",
    "subject": "260225-0002-5501",
    "protocolCanonical": "http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1",
    "severity": "HIGH",
    "fhirPayload": {
      "resourceType": "CommunicationRequest",
      "status": "active",
      "priority": "urgent",
      "category": [{ "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/cce-action-type", "code": "CommunicationRequest" }] }],
      "subject": { "identifier": { "system": "http://openphc.org/fhir/patient-upid", "value": "260225-0002-5501" } },
      "payload": [{ "contentString": "[HIGH] CommunicationRequest for patient 260225-0002-5501 — step anc-visit-2 overdue (PlanDefinition/anc-high-risk|2.1)" }]
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
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Intelligence delivery not found |

---

### 1.3 Get Intelligence Delivery Audit Trail

**`GET /v1/intelligence-deliveries/{id}/audit`** — Retrieve the audit log entries for a intelligence delivery.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Intelligence delivery ID |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "e5f6a7b8-0001-4000-e000-000000000001",
      "intelligenceDeliveryId": "b2c3d4e5-0001-4000-b000-000000000001",
      "eventType": "CREATED",
      "actor": "system",
      "details": null,
      "timestamp": "2026-03-25T10:05:00Z"
    },
    {
      "id": "e5f6a7b8-0002-4000-e000-000000000002",
      "intelligenceDeliveryId": "b2c3d4e5-0001-4000-b000-000000000001",
      "eventType": "DISPATCHED",
      "actor": "system",
      "details": { "adaptorName": "Kigali South SMS Gateway", "endpointUrl": "https://sms.example.com/webhook" },
      "timestamp": "2026-03-25T10:05:01Z"
    },
    {
      "id": "e5f6a7b8-0003-4000-e000-000000000003",
      "intelligenceDeliveryId": "b2c3d4e5-0001-4000-b000-000000000001",
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
| `404` | Intelligence delivery not found |

---

### 1.4 Cancel Intelligence Delivery

**`POST /v1/intelligence-deliveries/{id}/cancel`** — Cancel a pending or failed intelligence delivery.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Intelligence delivery ID |

**Pre-conditions:**
- Only intelligence deliveries with status `PENDING` or `FAILED` can be cancelled.
- `EXECUTING`, `DELIVERED`, and `CANCELLED` runs cannot be cancelled.

**Response:** `200 OK`

```json
{
  "data": {
    "id": "b2c3d4e5-0001-4000-b000-000000000001",
    "status": "CANCELLED",
    "updatedAt": "2026-03-25T11:00:00Z"
  }
}
```

| Error Status | Condition |
|:-------------|:----------|
| `404` | Intelligence delivery not found |
| `422` | Status does not allow cancellation |

**Side Effects:**
- Creates a `intelligence_delivery_audit_log` entry with `event_type = 'CANCELLED'`

---

## 2. Destination Adaptor Mappings

Destination Adaptor Mappings define the **1:1 routing** between an intelligence destination and a Receiver Adaptor. Each destination (e.g., `supervisor`, `patient-reminder`) maps to exactly one adaptor. The `destination` column is unique — attempting to create a duplicate mapping returns `409 Conflict`.

**Required scope**: `destination-adaptor-mappings:read` (GET), `destination-adaptor-mappings:write` (POST, PUT, DELETE)

---

### 2.1 Create Destination Adaptor Mapping

**`POST /v1/destination-adaptor-mappings`** — Create a new destination-to-adaptor mapping.

**Request Body**

```json
{
  "destination": "supervisor",
  "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001"
}
```

**Response:** `201 Created`

```json
{
  "data": {
    "id": "d4e5f6a7-0001-4000-d000-000000000010",
    "destination": "supervisor",
    "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001",
    "receiverAdaptorName": "Kigali South SMS Gateway",
    "status": "ACTIVE",
    "createdAt": "2026-03-25T10:00:00Z",
    "updatedAt": "2026-03-25T10:00:00Z"
  }
}
```

| Error Status | Condition |
|:-------------|:----------|
| `400` | Missing required fields or invalid UUIDs |
| `404` | `receiverAdaptorId` does not exist |
| `409` | Mapping for `destination` already exists (destination is unique) |

---

### 2.2 List Destination Adaptor Mappings

**`GET /v1/destination-adaptor-mappings`** — Retrieve all destination-adaptor mappings with optional filters.

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `destination` | `String` | No | Filter by destination name |
| `receiverAdaptorId` | `UUID` | No | Filter by receiver adaptor |
| `status` | `String` | No | Filter by status: `ACTIVE`, `INACTIVE` |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "d4e5f6a7-0001-4000-d000-000000000010",
      "destination": "supervisor",
      "receiverAdaptorId": "c3d4e5f6-0001-4000-c000-000000000001",
      "receiverAdaptorName": "Kigali South SMS Gateway",
      "status": "ACTIVE",
      "createdAt": "2026-03-25T10:00:00Z",
      "updatedAt": "2026-03-25T10:00:00Z"
    },
    {
      "id": "d4e5f6a7-0002-4000-d000-000000000011",
      "destination": "patient-reminder",
      "receiverAdaptorId": "c3d4e5f6-0002-4000-c000-000000000002",
      "receiverAdaptorName": "WhatsApp Bot Adaptor",
      "status": "ACTIVE",
      "createdAt": "2026-03-25T10:00:00Z",
      "updatedAt": "2026-03-25T10:00:00Z"
    }
  ]
}
```

---

### 2.3 Get Destination Adaptor Mapping by ID

**`GET /v1/destination-adaptor-mappings/{id}`**

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Destination adaptor mapping ID |

**Response:** `200 OK` — `DestinationAdaptorMappingDto`

| Error Status | Condition |
|:-------------|:----------|
| `404` | Mapping not found |

---

### 2.4 Update Destination Adaptor Mapping

**`PUT /v1/destination-adaptor-mappings/{id}`** — Update status or receiver adaptor of an existing mapping.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Destination adaptor mapping ID |

**Request Body**

```json
{
  "receiverAdaptorId": "c3d4e5f6-0003-4000-c000-000000000003",
  "status": "INACTIVE"
}
```

> **Note:** `destination` is immutable. To change the destination name, delete the mapping and create a new one.

**Response:** `200 OK`

```json
{
  "data": {
    "id": "d4e5f6a7-0001-4000-d000-000000000010",
    "destination": "supervisor",
    "receiverAdaptorId": "c3d4e5f6-0003-4000-c000-000000000003",
    "receiverAdaptorName": "New SMS Gateway",
    "status": "INACTIVE",
    "createdAt": "2026-03-25T10:00:00Z",
    "updatedAt": "2026-03-25T14:00:00Z"
  }
}
```

| Error Status | Condition |
|:-------------|:----------|
| `400` | Invalid request body |
| `404` | Mapping not found |

---

### 2.5 Delete Destination Adaptor Mapping

**`DELETE /v1/destination-adaptor-mappings/{id}`** — Remove a destination-adaptor mapping.

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Destination adaptor mapping ID |

**Pre-conditions:**
- Only mappings with no `PENDING` or `EXECUTING` intelligence deliveries can be deleted.
- Mappings with historical intelligence deliveries (`DELIVERED`, `FAILED`, `CANCELLED`) can be deleted; the `intelligence_delivery.destination_adaptor_mapping_id` FK is preserved (soft reference).

**Response:** `204 No Content`

| Error Status | Condition |
|:-------------|:----------|
| `404` | Mapping not found |
| `409` | Mapping has active (PENDING/EXECUTING) intelligence deliveries |

---

## 3. Receiver Adaptors

Receiver Adaptors represent external **webhook endpoints** that receive intelligence actions. Routing from destinations to adaptors is managed via Destination Adaptor Mappings.

**Required scope**: `admin` (all operations)

---

### 3.1 Register Receiver Adaptor

**`POST /v1/receiver-adaptors`** — Register a new webhook endpoint.

**Request Body**

```json
{
  "name": "Kigali South SMS Gateway",
  "definition": {
    "resourceType": "Endpoint",
    "id": "kigali-south-sms",
    "status": "active",
    "connectionType": {
      "system": "http://terminology.hl7.org/CodeSystem/endpoint-connection-type",
      "code": "hl7-fhir-rest",
      "display": "HL7 FHIR REST"
    },
    "name": "Kigali South SMS Gateway",
    "payloadType": [
      {
        "coding": [
          { "system": "http://hl7.org/fhir/resource-types", "code": "CommunicationRequest" }
        ]
      }
    ],
    "payloadMimeType": ["application/fhir+json"],
    "address": "https://sms-gateway.example.com/webhook/intelligence"
  },
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
  "data": {
    "id": "c3d4e5f6-0001-4000-c000-000000000001",
    "name": "Kigali South SMS Gateway",
    "definition": {
      "resourceType": "Endpoint",
      "id": "kigali-south-sms",
      "status": "active",
      "connectionType": {
        "system": "http://terminology.hl7.org/CodeSystem/endpoint-connection-type",
        "code": "hl7-fhir-rest",
        "display": "HL7 FHIR REST"
      },
      "name": "Kigali South SMS Gateway",
      "payloadType": [
        {
          "coding": [
            { "system": "http://hl7.org/fhir/resource-types", "code": "CommunicationRequest" }
          ]
        }
      ],
      "payloadMimeType": ["application/fhir+json"],
      "address": "https://sms-gateway.example.com/webhook/intelligence"
    },
    "status": "ACTIVE",
    "config": {
      "authHeader": "X-API-Key",
      "authValue": "sk-***123",
      "timeoutMs": 10000
    },
    "createdAt": "2026-03-20T08:00:00Z",
    "updatedAt": "2026-03-20T08:00:00Z"
  }
}
```

> **Note:** `authValue` is masked in all API responses. Full credentials are accepted on write operations (`POST`, `PUT`) but never returned.

| Error Status | Condition |
|:-------------|:----------|
| `400` | Invalid request body (missing required fields, invalid URL, `definition.resourceType` not `Endpoint`, `definition.name` mismatch) |
| `409` | Adaptor `name` already exists |

---

### 3.2 List Receiver Adaptors

**`GET /v1/receiver-adaptors`** — Retrieve all receiver adaptors.

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | `String` | No | Filter by status: `ACTIVE`, `INACTIVE` |

**Response:** `200 OK`

```json
{
  "data": [
    {
      "id": "c3d4e5f6-0001-4000-c000-000000000001",
      "name": "Kigali South SMS Gateway",
      "definition": {
        "resourceType": "Endpoint",
        "id": "kigali-south-sms",
        "status": "active",
        "connectionType": {
          "system": "http://terminology.hl7.org/CodeSystem/endpoint-connection-type",
          "code": "hl7-fhir-rest",
          "display": "HL7 FHIR REST"
        },
        "name": "Kigali South SMS Gateway",
        "payloadType": [
          {
            "coding": [
              { "system": "http://hl7.org/fhir/resource-types", "code": "CommunicationRequest" }
            ]
          }
        ],
        "payloadMimeType": ["application/fhir+json"],
        "address": "https://sms-gateway.example.com/webhook/intelligence"
      },
      "status": "ACTIVE",
      "config": { "authHeader": "X-API-Key", "authValue": "sk-***123" },
      "createdAt": "2026-03-20T08:00:00Z",
      "updatedAt": "2026-03-20T08:00:00Z"
    }
  ]
}
```

---

### 3.3 Get Receiver Adaptor by ID

**`GET /v1/receiver-adaptors/{id}`**

| Path Parameter | Type | Description |
|:---------------|:-----|:------------|
| `id` | `UUID` | Receiver adaptor ID |

**Response:** `200 OK` — `ReceiverAdaptorDto`

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
  "name": "Kigali South SMS Gateway",
  "definition": {
    "resourceType": "Endpoint",
    "id": "kigali-south-sms",
    "status": "active",
    "connectionType": {
      "system": "http://terminology.hl7.org/CodeSystem/endpoint-connection-type",
      "code": "hl7-fhir-rest",
      "display": "HL7 FHIR REST"
    },
    "name": "Kigali South SMS Gateway",
    "payloadType": [
      {
        "coding": [
          { "system": "http://hl7.org/fhir/resource-types", "code": "CommunicationRequest" }
        ]
      }
    ],
    "payloadMimeType": ["application/fhir+json"],
    "address": "https://sms-gateway-v2.example.com/webhook/intelligence"
  },
  "status": "ACTIVE",
  "config": {
    "authHeader": "Authorization",
    "authValue": "Bearer tok-xyz789",
    "timeoutMs": 15000
  }
}
```

> **Note:** `authValue` is accepted in full on `PUT` requests but will be masked in the response.

**Response:** `200 OK` — Updated `ReceiverAdaptorDto`

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
- Only adaptors with no `PENDING` or `EXECUTING` intelligence deliveries (via destination-adaptor mappings) can be deleted.
- All destination-adaptor mappings referencing this adaptor must be deleted or inactive first.

**Response:** `204 No Content`

| Error Status | Condition |
|:-------------|:----------|
| `404` | Receiver adaptor not found |
| `409` | Adaptor has active intelligence deliveries or active destination-adaptor mappings |

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

### Health Check Detail

**`GET /actuator/health`**

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL", "validationQuery": "isValid()" } },
    "kafka": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

---

## 5. Error Response Format

All error responses follow a consistent envelope:

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Intelligence delivery not found: b2c3d4e5-0001-4000-b000-000000000099",
    "path": "/v1/intelligence-deliveries/b2c3d4e5-0001-4000-b000-000000000099",
    "timestamp": "2026-03-25T10:05:00Z"
  }
}
```

### HTTP Status Codes

| Status | Code | Description |
|--------|------|-------------|
| `400` | `BAD_REQUEST` | Invalid request body, missing parameters |
| `404` | `NOT_FOUND` | Resource does not exist |
| `409` | `CONFLICT` | Uniqueness constraint violation |
| `422` | `UNPROCESSABLE_ENTITY` | Business rule violation (invalid state transition, active dependencies) |
| `500` | `INTERNAL_ERROR` | Unexpected server error |
