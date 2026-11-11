package org.openphc.cce.intelligence.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FhirPayloadBuilder}: the FHIR R4 resource each action type is delivered as, for
 * carrying which action fired, its title and why, and the fallbacks when those are absent.
 */
class FhirPayloadBuilderTest {

    private static final String CANONICAL =
            "http://example.org/PlanDefinition/chronic-care|1.0.0";
    private static final String EXT = "http://openphc.org/fhir/StructureDefinition/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FhirPayloadBuilder builder = new FhirPayloadBuilder(objectMapper);
    private final UUID deliveryId = UUID.randomUUID();

    /** A trigger as the SLA service publishes it for an overdue step. */
    private IntelligenceTriggerEvent overdueTrigger(String actionType) {
        return IntelligenceTriggerEvent.builder()
                .id(UUID.randomUUID())
                .intelligenceEventId(UUID.randomUUID())
                .subject("patient-001")
                .actionType(actionType)
                .severity("HIGH")
                .intelligenceDestination("care-team")
                .stepStatus("not-started")
                .slaStatus("overdue")
                .actionId("follow-up-visit")
                .intelligenceActionId("follow-up-visit-overdue-alert")
                .title("Follow-up visit overdue: contact the patient")
                .triggerReason("overdue")
                .protocolCanonical(CANONICAL)
                .build();
    }

    /** A sparse trigger: an untitled action, no intelligence action id, reason, step or SLA status. */
    private IntelligenceTriggerEvent sparseTrigger(String actionType) {
        return IntelligenceTriggerEvent.builder()
                .id(UUID.randomUUID())
                .intelligenceEventId(UUID.randomUUID())
                .subject("patient-001")
                .actionType(actionType)
                .severity("LOW")
                .intelligenceDestination("supervisor")
                .actionId("follow-up-visit")
                .protocolCanonical(CANONICAL)
                .build();
    }

    @Test
    void communicationRequest_carriesTheActionItsTitleAndWhyItFired() {
        JsonNode resource = builder.buildPayload(overdueTrigger("CommunicationRequest"), deliveryId);

        assertThat(resource.path("resourceType").asText()).isEqualTo("CommunicationRequest");
        assertThat(resource.path("priority").asText()).isEqualTo("urgent");
        assertThat(resource.path("payload").get(0).path("contentString").asText())
                .isEqualTo("Follow-up visit overdue: contact the patient");

        // reasonCode is 0..* on CommunicationRequest
        assertThat(resource.path("reasonCode").isArray()).isTrue();
        assertThat(resource.path("reasonCode").get(0).path("coding").get(0).path("code").asText())
                .isEqualTo("overdue");

        // about: protocol, step and action, each a logical reference (no invalid literal reference)
        JsonNode about = resource.path("about");
        assertThat(about).hasSize(3);
        about.forEach(reference -> assertThat(reference.has("reference")).isFalse());
        assertThat(about.get(0).path("type").asText()).isEqualTo("PlanDefinition");
        assertThat(about.get(0).path("identifier").path("value").asText()).isEqualTo(CANONICAL);
        assertThat(about.get(1).path("identifier").path("value").asText()).isEqualTo("follow-up-visit");
        assertThat(about.get(2).path("identifier").path("system").asText())
                .isEqualTo(FhirPayloadBuilder.INTELLIGENCE_ACTION_SYSTEM);
        assertThat(about.get(2).path("identifier").path("value").asText()).isEqualTo("follow-up-visit-overdue-alert");
        assertThat(about.get(2).path("display").asText()).isEqualTo("Follow-up visit overdue: contact the patient");

        assertThat(extensionCode(resource, "cce-step-status")).isEqualTo("not-started");
        assertThat(extensionCode(resource, "cce-sla-status")).isEqualTo("overdue");
    }

    @Test
    void task_carriesTheSameInTaskShapes() {
        JsonNode resource = builder.buildPayload(overdueTrigger("Task"), deliveryId);

        assertThat(resource.path("resourceType").asText()).isEqualTo("Task");
        assertThat(resource.path("description").asText()).isEqualTo("Follow-up visit overdue: contact the patient");
        // reasonCode is 0..1 on Task — a single CodeableConcept, not an array
        assertThat(resource.path("reasonCode").isObject()).isTrue();
        assertThat(resource.path("reasonCode").path("coding").get(0).path("code").asText()).isEqualTo("overdue");
        assertThat(resource.path("focus").path("identifier").path("value").asText())
                .isEqualTo("follow-up-visit-overdue-alert");
        assertThat(resource.path("focus").path("display").asText())
                .isEqualTo("Follow-up visit overdue: contact the patient");
        assertThat(extensionCode(resource, "cce-sla-status")).isEqualTo("overdue");
    }

    @Test
    void sparseTrigger_fallsBackToTheSummary_andOmitsWhatItDoesNotHave() {
        JsonNode communication = builder.buildPayload(sparseTrigger("CommunicationRequest"), deliveryId);

        assertThat(communication.path("payload").get(0).path("contentString").asText())
                .isEqualTo("[LOW] CommunicationRequest for patient patient-001 — step follow-up-visit (" + CANONICAL + ")");
        assertThat(communication.has("reasonCode")).isFalse();
        assertThat(communication.path("about")).hasSize(2);

        JsonNode task = builder.buildPayload(sparseTrigger("Task"), deliveryId);
        assertThat(task.has("reasonCode")).isFalse();
        assertThat(task.has("focus")).isFalse();
    }

    @Test
    void extensionsWithNoValueAreLeftOut() {
        // FHIR forbids an element with neither a value nor children: no "valueCode": null.
        JsonNode resource = builder.buildPayload(sparseTrigger("CommunicationRequest"), deliveryId);

        assertThat(extensionUrls(resource)).containsExactly(
                EXT + "cce-severity", EXT + "cce-intelligence-event-id");
        resource.path("extension").forEach(extension ->
                assertThat(extension.properties()).allSatisfy(field -> assertThat(field.getValue().isNull()).isFalse()));
    }

    @Test
    void serviceRequest_isPassedThroughUnchanged() {
        IntelligenceTriggerEvent event = overdueTrigger("ServiceRequest");
        JsonNode original = objectMapper.createObjectNode().put("resourceType", "ServiceRequest").put("id", "sr-1");
        event.setEventPayload(original);

        assertThat(builder.buildPayload(event, deliveryId)).isSameAs(original);
    }

    private static String extensionCode(JsonNode resource, String name) {
        for (JsonNode extension : resource.path("extension")) {
            if ((EXT + name).equals(extension.path("url").asText())) {
                return extension.path("valueCode").asText();
            }
        }
        return null;
    }

    private static List<String> extensionUrls(JsonNode resource) {
        List<String> urls = new ArrayList<>();
        resource.path("extension").forEach(extension -> urls.add(extension.path("url").asText()));
        return urls;
    }
}
