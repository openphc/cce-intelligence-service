package org.openphc.cce.intelligence.kafka.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntelligenceTriggerEvent {

    private UUID id;
    private String subject;
    private UUID intelligenceEventId;
    private UUID actionDefinitionId;
    private UUID protocolDefinitionId;
    private String actionType;
    private String severity;
    private String intelligenceDestination;
    /** The step's status code (e.g. "not-started", "completed"). */
    private String stepStatus;
    /** The step's SLA status (e.g. "overdue"), null while no threshold has been judged. */
    private String slaStatus;
    /** The step the intelligence action sits under (e.g. "follow-up-visit"). */
    private String actionId;
    /** The intelligence action that fired (e.g. "follow-up-visit-overdue-alert"). */
    private String intelligenceActionId;
    /** The intelligence action's title from the PlanDefinition. */
    private String title;
    /**
     * The kind of communication, when the intelligence action declares one: an HL7
     * communication-category code (alert, notification, reminder, instruction).
     */
    private String category;
    /**
     * Who the intelligence action is for, from its PlanDefinition {@code participant.type}: patient,
     * practitioner, related-person or device. Absent when it declares none.
     */
    private List<String> participantTypes;
    /** Why it fired: "completion", or the deviation type ("overdue", "missed", "order_violation"). */
    private String triggerReason;
    private String protocolCanonical;
    private OffsetDateTime detectedAt;
    private JsonNode eventPayload;
}
