package org.openphc.cce.intelligence.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FhirPayloadBuilder {

    static final String PROTOCOL_CANONICAL_SYSTEM = "http://openphc.org/fhir/protocol-canonical";
    static final String PROTOCOL_STEP_SYSTEM = "http://openphc.org/fhir/protocol-step-id";
    static final String INTELLIGENCE_ACTION_SYSTEM = "http://openphc.org/fhir/intelligence-action-id";
    static final String TRIGGER_REASON_SYSTEM = "http://openphc.org/fhir/CodeSystem/cce-trigger-reason";

    private final ObjectMapper objectMapper;

    /**
     * Builds a FHIR R4-compliant payload from the trigger event.
     * CommunicationRequest → FHIR CommunicationRequest
     * Task → FHIR Task
     * ServiceRequest (with eventPayload) → passthrough
     */
    public JsonNode buildPayload(IntelligenceTriggerEvent event, UUID intelligenceDeliveryId) {
        String actionType = event.getActionType();

        // ServiceRequest: pass through the original event payload from the compliance service
        if ("ServiceRequest".equals(actionType) && event.getEventPayload() != null) {
            return event.getEventPayload();
        }
        if ("Task".equals(actionType)) {
            return buildTask(event, intelligenceDeliveryId);
        }
        return buildCommunicationRequest(event, intelligenceDeliveryId);
    }

    private JsonNode buildCommunicationRequest(IntelligenceTriggerEvent event, UUID deliveryId) {
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("resourceType", "CommunicationRequest");
        resource.put("status", "active");
        resource.put("priority", mapSeverityToFhirPriority(event.getSeverity()));

        // Identifier
        ArrayNode identifiers = resource.putArray("identifier");
        ObjectNode identifier = identifiers.addObject();
        identifier.put("system", "http://openphc.org/fhir/intelligence-delivery-id");
        identifier.put("value", deliveryId != null ? deliveryId.toString() : "pending");

        // Category
        ArrayNode categories = resource.putArray("category");
        ObjectNode category = categories.addObject();
        ArrayNode codings = category.putArray("coding");
        ObjectNode coding = codings.addObject();
        coding.put("system", "http://openphc.org/fhir/CodeSystem/cce-action-type");
        coding.put("code", resolveActionType(event));
        coding.put("display", resolveActionType(event));

        // Subject (FHIR identifier-based reference)
        ObjectNode subject = resource.putObject("subject");
        ObjectNode subjectIdentifier = subject.putObject("identifier");
        subjectIdentifier.put("system", "http://openphc.org/fhir/patient-upid");
        subjectIdentifier.put("value", event.getSubject());

        // About: the protocol, the step and the intelligence action, as logical (identifier) references.
        // A canonical is not a resource id, so "PlanDefinition/<canonical>" would not be a valid
        // literal reference.
        ArrayNode about = resource.putArray("about");
        addIdentifierReference(about, "PlanDefinition", PROTOCOL_CANONICAL_SYSTEM,
                event.getProtocolCanonical(), null);
        addIdentifierReference(about, null, PROTOCOL_STEP_SYSTEM, event.getActionId(), event.getActionId());
        addIdentifierReference(about, null, INTELLIGENCE_ACTION_SYSTEM,
                event.getIntelligenceActionId(), event.getTitle());

        // Payload — the action's title from the PlanDefinition, else a generated summary
        ArrayNode payload = resource.putArray("payload");
        ObjectNode payloadContent = payload.addObject();
        payloadContent.put("contentString", buildMessage(event));

        // ReasonCode (0..* on CommunicationRequest): why it fired
        ObjectNode reason = buildReasonCode(event);
        if (reason != null) {
            resource.putArray("reasonCode").add(reason);
        }

        // Recipient
        ArrayNode recipients = resource.putArray("recipient");
        ObjectNode recipient = recipients.addObject();
        recipient.put("display", event.getIntelligenceDestination());

        // AuthoredOn
        resource.put("authoredOn", OffsetDateTime.now().toString());

        // CCE Extensions
        addExtensions(resource, event);

        return resource;
    }

    private JsonNode buildTask(IntelligenceTriggerEvent event, UUID deliveryId) {
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("resourceType", "Task");
        resource.put("status", "requested");
        resource.put("intent", "order");
        resource.put("priority", mapSeverityToFhirPriority(event.getSeverity()));

        // Identifier
        ArrayNode identifiers = resource.putArray("identifier");
        ObjectNode identifier = identifiers.addObject();
        identifier.put("system", "http://openphc.org/fhir/intelligence-delivery-id");
        identifier.put("value", deliveryId != null ? deliveryId.toString() : "pending");

        // Code
        ObjectNode code = resource.putObject("code");
        ArrayNode codings = code.putArray("coding");
        ObjectNode coding = codings.addObject();
        coding.put("system", "http://openphc.org/fhir/CodeSystem/cce-action-type");
        coding.put("code", "Task");
        coding.put("display", "Task");

        // For (subject - FHIR identifier-based reference)
        ObjectNode forRef = resource.putObject("for");
        ObjectNode forIdentifier = forRef.putObject("identifier");
        forIdentifier.put("system", "http://openphc.org/fhir/patient-upid");
        forIdentifier.put("value", event.getSubject());

        // Focus: the intelligence action this task carries out
        if (event.getIntelligenceActionId() != null) {
            ObjectNode focus = resource.putObject("focus");
            ObjectNode focusIdentifier = focus.putObject("identifier");
            focusIdentifier.put("system", INTELLIGENCE_ACTION_SYSTEM);
            focusIdentifier.put("value", event.getIntelligenceActionId());
            if (event.getTitle() != null) {
                focus.put("display", event.getTitle());
            }
        }

        // Description — the action's title from the PlanDefinition, else a generated summary
        resource.put("description", buildMessage(event));

        // ReasonCode (0..1 on Task): why it fired
        ObjectNode reason = buildReasonCode(event);
        if (reason != null) {
            resource.set("reasonCode", reason);
        }

        // AuthoredOn
        resource.put("authoredOn", OffsetDateTime.now().toString());

        // CCE Extensions
        addExtensions(resource, event);

        return resource;
    }

    private JsonNode buildServiceRequest(IntelligenceTriggerEvent event, UUID deliveryId) {
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("resourceType", "ServiceRequest");
        resource.put("status", "active");
        resource.put("intent", "order");
        resource.put("priority", mapSeverityToFhirPriority(event.getSeverity()));

        // Identifier
        ArrayNode identifiers = resource.putArray("identifier");
        ObjectNode identifier = identifiers.addObject();
        identifier.put("system", "http://openphc.org/fhir/intelligence-delivery-id");
        identifier.put("value", deliveryId != null ? deliveryId.toString() : "pending");

        // Code
        ObjectNode code = resource.putObject("code");
        ArrayNode codings = code.putArray("coding");
        ObjectNode coding = codings.addObject();
        coding.put("system", "http://openphc.org/fhir/CodeSystem/cce-action-type");
        coding.put("code", "ServiceRequest");
        coding.put("display", "ServiceRequest");

        // Subject (FHIR identifier-based reference)
        ObjectNode subject = resource.putObject("subject");
        ObjectNode subjectIdentifier = subject.putObject("identifier");
        subjectIdentifier.put("system", "http://openphc.org/fhir/patient-upid");
        subjectIdentifier.put("value", event.getSubject());

        // Note / description
        ArrayNode notes = resource.putArray("note");
        ObjectNode note = notes.addObject();
        note.put("text", buildSummary(event));

        // AuthoredOn
        resource.put("authoredOn", OffsetDateTime.now().toString());

        // CCE Extensions
        addExtensions(resource, event);

        return resource;
    }

    private void addExtensions(ObjectNode resource, IntelligenceTriggerEvent event) {
        ArrayNode extensions = resource.putArray("extension");

        ObjectNode severityExt = extensions.addObject();
        severityExt.put("url", "http://openphc.org/fhir/StructureDefinition/cce-severity");
        severityExt.put("valueCode", event.getSeverity().toLowerCase());

        ObjectNode eventIdExt = extensions.addObject();
        eventIdExt.put("url", "http://openphc.org/fhir/StructureDefinition/cce-intelligence-event-id");
        eventIdExt.put("valueId", event.getIntelligenceEventId().toString());

        // FHIR forbids an element with neither a value nor children, so absent values are left out.
        addCodeExtension(extensions, "http://openphc.org/fhir/StructureDefinition/cce-step-status",
                event.getStepStatus());
        addCodeExtension(extensions, "http://openphc.org/fhir/StructureDefinition/cce-sla-status",
                event.getSlaStatus());
    }

    private void addCodeExtension(ArrayNode extensions, String url, String code) {
        if (code == null || code.isBlank()) return;
        ObjectNode extension = extensions.addObject();
        extension.put("url", url);
        extension.put("valueCode", code);
    }

    /** A Reference by identifier (no literal reference). Skipped when there is no value. */
    private void addIdentifierReference(ArrayNode references, String type, String system, String value,
                                        String display) {
        if (value == null || value.isBlank()) return;
        ObjectNode reference = references.addObject();
        if (type != null) {
            reference.put("type", type);
        }
        ObjectNode identifier = reference.putObject("identifier");
        identifier.put("system", system);
        identifier.put("value", value);
        if (display != null) {
            reference.put("display", display);
        }
    }

    /** The trigger reason as a CodeableConcept, or null when the event has none. */
    private ObjectNode buildReasonCode(IntelligenceTriggerEvent event) {
        if (event.getTriggerReason() == null || event.getTriggerReason().isBlank()) return null;
        ObjectNode reason = objectMapper.createObjectNode();
        ObjectNode coding = reason.putArray("coding").addObject();
        coding.put("system", TRIGGER_REASON_SYSTEM);
        coding.put("code", event.getTriggerReason());
        return reason;
    }

    private String buildMessage(IntelligenceTriggerEvent event) {
        return event.getTitle() != null && !event.getTitle().isBlank() ? event.getTitle() : buildSummary(event);
    }

    private String mapSeverityToFhirPriority(String severity) {
        return switch (severity.toUpperCase()) {
            case "LOW" -> "routine";
            case "MEDIUM", "HIGH" -> "urgent";
            case "CRITICAL" -> "asap";
            default -> "routine";
        };
    }

    private String resolveActionType(IntelligenceTriggerEvent event) {
        return event.getActionType();
    }

    private String buildSummary(IntelligenceTriggerEvent event) {
        return String.format("[%s] %s for patient %s — step %s (%s)",
                event.getSeverity(),
                resolveActionType(event),
                event.getSubject(),
                event.getActionId(),
                event.getProtocolCanonical());
    }
}
