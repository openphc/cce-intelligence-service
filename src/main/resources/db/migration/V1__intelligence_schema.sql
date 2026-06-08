-- ============================================================================
-- V1: Intelligence Service Schema
-- Database: ccedb (shared with Compliance Service)
-- Tables: receiver_adaptor, destination_adaptor_mapping, intelligence_delivery,
--         intelligence_delivery_audit_log
-- ============================================================================

-- ============================================================================
-- 1. receiver_adaptor
-- ============================================================================
CREATE TABLE receiver_adaptor (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    name            VARCHAR     NOT NULL,
    definition      JSONB       NOT NULL,
    status          VARCHAR     NOT NULL DEFAULT 'ACTIVE',
    config          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT receiver_adaptor_pkey PRIMARY KEY (id),
    CONSTRAINT receiver_adaptor_name_key UNIQUE (name),
    CONSTRAINT receiver_adaptor_definition_type_check
        CHECK (definition->>'resourceType' = 'Endpoint'),
    CONSTRAINT receiver_adaptor_definition_address_check
        CHECK (definition->>'address' IS NOT NULL),
    CONSTRAINT receiver_adaptor_status_check
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- ============================================================================
-- 2. destination_adaptor_mapping
-- ============================================================================
CREATE TABLE destination_adaptor_mapping (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    destination             VARCHAR     NOT NULL,
    receiver_adaptor_id     UUID        NOT NULL,
    status                  VARCHAR     NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT destination_adaptor_mapping_pkey PRIMARY KEY (id),
    CONSTRAINT destination_adaptor_mapping_destination_key UNIQUE (destination),
    CONSTRAINT destination_adaptor_mapping_receiver_adaptor_id_fkey
        FOREIGN KEY (receiver_adaptor_id) REFERENCES receiver_adaptor(id),
    CONSTRAINT destination_adaptor_mapping_status_check
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- Adaptor lookup
CREATE INDEX idx_destination_adaptor_mapping_adaptor
    ON destination_adaptor_mapping (receiver_adaptor_id);

-- Active mappings partial index
CREATE INDEX idx_destination_adaptor_mapping_active
    ON destination_adaptor_mapping (status)
    WHERE status = 'ACTIVE';

-- ============================================================================
-- 3. intelligence_delivery
-- ============================================================================
CREATE TABLE intelligence_delivery (
    id                              UUID        NOT NULL DEFAULT gen_random_uuid(),
    intelligence_event_id           UUID        NOT NULL,
    action_definition_id            UUID        NOT NULL,
    destination_adaptor_mapping_id  UUID,
    action_type                     VARCHAR     NOT NULL,
    status                          VARCHAR     NOT NULL,
    subject                         VARCHAR     NOT NULL,
    protocol_canonical              VARCHAR     NOT NULL,
    action_id                       VARCHAR     NOT NULL,
    severity                        VARCHAR     NOT NULL,
    destination                     VARCHAR     NOT NULL,
    fhir_payload                    JSONB       NOT NULL,
    delivery_result                 JSONB,
    attempt_count                   INTEGER     NOT NULL DEFAULT 0,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at                    TIMESTAMPTZ,

    CONSTRAINT intelligence_delivery_pkey PRIMARY KEY (id),
    CONSTRAINT intelligence_delivery_destination_adaptor_mapping_id_fkey
        FOREIGN KEY (destination_adaptor_mapping_id) REFERENCES destination_adaptor_mapping(id),
    CONSTRAINT intelligence_delivery_event_mapping_key
        UNIQUE (intelligence_event_id, destination_adaptor_mapping_id),
    CONSTRAINT intelligence_delivery_action_type_check
        CHECK (action_type IN ('CommunicationRequest', 'Task', 'ServiceRequest')),
    CONSTRAINT intelligence_delivery_status_check
        CHECK (status IN ('PENDING', 'EXECUTING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    CONSTRAINT intelligence_delivery_severity_check
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE INDEX idx_intelligence_delivery_intelligence_event
    ON intelligence_delivery (intelligence_event_id);

CREATE INDEX idx_intelligence_delivery_status
    ON intelligence_delivery (status);

CREATE INDEX idx_intelligence_delivery_subject
    ON intelligence_delivery (subject);

CREATE INDEX idx_intelligence_delivery_failed
    ON intelligence_delivery (status)
    WHERE status = 'FAILED';

-- ============================================================================
-- 4. intelligence_delivery_audit_log
-- ============================================================================
CREATE TABLE intelligence_delivery_audit_log (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    intelligence_delivery_id UUID       NOT NULL,
    event_type              VARCHAR     NOT NULL,
    actor                   VARCHAR     NOT NULL DEFAULT 'system',
    details                 JSONB,
    timestamp               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT intelligence_delivery_audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT intelligence_delivery_audit_log_intelligence_delivery_id_fkey
        FOREIGN KEY (intelligence_delivery_id) REFERENCES intelligence_delivery(id)
);

CREATE INDEX idx_intelligence_delivery_audit_log_run
    ON intelligence_delivery_audit_log (intelligence_delivery_id);

CREATE INDEX idx_intelligence_delivery_audit_log_timestamp
    ON intelligence_delivery_audit_log (timestamp);

-- ============================================================================
-- 5. Replica Identity (for logical replication / CDC)
-- ============================================================================
ALTER TABLE receiver_adaptor REPLICA IDENTITY FULL;
ALTER TABLE destination_adaptor_mapping REPLICA IDENTITY FULL;
ALTER TABLE intelligence_delivery REPLICA IDENTITY FULL;
ALTER TABLE intelligence_delivery_audit_log REPLICA IDENTITY FULL;
