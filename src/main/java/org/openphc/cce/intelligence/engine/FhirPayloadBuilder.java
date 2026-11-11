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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FhirPayloadBuilder {

    static final String PROTOCOL_CANONICAL_SYSTEM = "http://openphc.org/fhir/protocol-canonical";
    static final String TRIGGER_REASON_SYSTEM = "http://openphc.org/fhir/CodeSystem/cce-trigger-reason";
    static final String COMMUNICATION_CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/communication-category";
    /** Used when the intelligence action declares no category. */
    static final String DEFAULT_COMMUNICATION_CATEGORY = "notification";
    static final String PROTOCOL_STEP_EXTENSION = "http://openphc.org/fhir/StructureDefinition/cce-protocol-step-id";
    static final String INTELLIGENCE_ACTION_EXTENSION = "http://openphc.org/fhir/StructureDefinition/cce-intelligence-action-id";
    /** The routing destination the delivery went to (e.g. a destination code), not a FHIR recipient. */
    static final String DESTINATION_EXTENSION = "http://openphc.org/fhir/StructureDefinition/cce-destination";

    /**
     * FHIR dateTime with seconds always present and millisecond precision, in UTC.
     * OffsetDateTime.toString() drops zero seconds ("…T12:52Z", not a valid FHIR dateTime) and
     * writes nanoseconds, which many receivers cannot parse.
     */
    private static final DateTimeFormatter FHIR_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

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

        // Category: the kind of communication (HL7 communication-category), as the intelligence action
        // declares it in the PlanDefinition; a plain notification when it declares none.
        String categoryCode = event.getCategory() != null && !event.getCategory().isBlank()
                ? event.getCategory() : DEFAULT_COMMUNICATION_CATEGORY;
        ObjectNode coding = resource.putArray("category").addObject().putArray("coding").addObject();
        coding.put("system", COMMUNICATION_CATEGORY_SYSTEM);
        coding.put("code", categoryCode);
        coding.put("display", capitalize(categoryCode));

        // Subject (FHIR identifier-based reference)
        resource.set("subject", patientReference(event));

        // About: the protocol, as a logical (identifier) reference. A canonical is not a resource id, so
        // "PlanDefinition/<canonical>" would not be a valid literal reference. The step and the
        // intelligence action are elements of that PlanDefinition, not resources, so they are carried as
        // extensions (see addExtensions) rather than as references.
        ArrayNode about = resource.putArray("about");
        addIdentifierReference(about, "PlanDefinition", PROTOCOL_CANONICAL_SYSTEM,
                event.getProtocolCanonical(), null);
        if (about.isEmpty()) {
            resource.remove("about");
        }

        // Payload — the action's title from the PlanDefinition, else a generated summary
        ArrayNode payload = resource.putArray("payload");
        ObjectNode payloadContent = payload.addObject();
        payloadContent.put("contentString", buildMessage(event));

        // ReasonCode (0..* on CommunicationRequest): why it fired
        ObjectNode reason = buildReasonCode(event);
        if (reason != null) {
            resource.putArray("reasonCode").add(reason);
        }

        // Recipient: who the message is for. Set only when the intelligence action declares a patient
        // participant — then it is the subject. A practitioner or organization recipient depends on the
        // patient (e.g. their own PHC) and is left to the receiving system; the routing destination is
        // not a recipient and travels as the cce-destination extension instead.
        if (event.getParticipantTypes() != null && event.getParticipantTypes().contains("patient")) {
            resource.putArray("recipient").add(patientReference(event));
        }

        // AuthoredOn
        resource.put("authoredOn", fhirNow());

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
        resource.set("for", patientReference(event));

        // No focus: Task.focus is the resource the task acts on, and an intelligence action is an element
        // of the PlanDefinition, not a resource. The step and the action travel as extensions.

        // Description — the action's title from the PlanDefinition, else a generated summary
        resource.put("description", buildMessage(event));

        // ReasonCode (0..1 on Task): why it fired
        ObjectNode reason = buildReasonCode(event);
        if (reason != null) {
            resource.set("reasonCode", reason);
        }

        // AuthoredOn
        resource.put("authoredOn", fhirNow());

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
        resource.set("subject", patientReference(event));

        // Note / description
        ArrayNode notes = resource.putArray("note");
        ObjectNode note = notes.addObject();
        note.put("text", buildSummary(event));

        // AuthoredOn
        resource.put("authoredOn", fhirNow());

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
        // Where it comes from in the protocol, and where CCE routed it
        addStringExtension(extensions, PROTOCOL_STEP_EXTENSION, event.getActionId());
        addStringExtension(extensions, INTELLIGENCE_ACTION_EXTENSION, event.getIntelligenceActionId());
        addStringExtension(extensions, DESTINATION_EXTENSION, event.getIntelligenceDestination());
    }

    /** The patient as a typed logical reference, by UPID. */
    private ObjectNode patientReference(IntelligenceTriggerEvent event) {
        ObjectNode reference = objectMapper.createObjectNode();
        reference.put("type", "Patient");
        ObjectNode identifier = reference.putObject("identifier");
        identifier.put("system", "http://openphc.org/fhir/patient-upid");
        identifier.put("value", event.getSubject());
        return reference;
    }

    private void addStringExtension(ArrayNode extensions, String url, String value) {
        if (value == null || value.isBlank()) return;
        ObjectNode extension = extensions.addObject();
        extension.put("url", url);
        extension.put("valueString", value);
    }

    private static String fhirNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(FHIR_DATE_TIME);
    }

    private static String capitalize(String code) {
        return code.isEmpty() ? code : Character.toUpperCase(code.charAt(0)) + code.substring(1);
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
        // "order_violation" → "Order violation"
        coding.put("display", capitalize(event.getTriggerReason().replace('_', ' ')));
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
