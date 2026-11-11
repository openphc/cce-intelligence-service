package org.openphc.cce.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.openphc.cce.intelligence.domain.entity.DestinationAdaptorMapping;
import org.openphc.cce.intelligence.domain.entity.IntelligenceDelivery;
import org.openphc.cce.intelligence.domain.entity.ReceiverAdaptor;
import org.openphc.cce.intelligence.domain.enums.IntelligenceDeliveryStatus;
import org.openphc.cce.intelligence.domain.repository.DestinationAdaptorMappingRepository;
import org.openphc.cce.intelligence.domain.repository.IntelligenceDeliveryAuditLogRepository;
import org.openphc.cce.intelligence.domain.repository.IntelligenceDeliveryRepository;
import org.openphc.cce.intelligence.domain.repository.ReceiverAdaptorRepository;
import org.openphc.cce.intelligence.kafka.consumer.IntelligenceTriggerConsumer;
import org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.intelligence.webhook.WebhookDeliveryClient;
import org.openphc.cce.intelligence.webhook.WebhookResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.bootstrap-servers=localhost:19092",
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.admin.fail-fast=false",
                "cce.kafka.topics.intelligence-triggers=test-topic"
        }
)
@EnableAutoConfiguration(exclude = {KafkaAutoConfiguration.class})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntelligenceEndToEndIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }
                @Override
                public void commit(TransactionStatus status) {}
                @Override
                public void rollback(TransactionStatus status) {}
            };
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }
    }

    @Autowired
    private IntelligenceTriggerConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IntelligenceDeliveryRepository deliveryRepository;

    @MockBean
    private IntelligenceDeliveryAuditLogRepository auditLogRepository;

    @MockBean
    private DestinationAdaptorMappingRepository mappingRepository;

    @MockBean
    private ReceiverAdaptorRepository adaptorRepository;

    @MockBean
    private WebhookDeliveryClient webhookDeliveryClient;

    private final List<IntelligenceDelivery> savedDeliveries = new CopyOnWriteArrayList<>();

    private static final UUID ADAPTOR_ID_1 = UUID.fromString("22222222-2222-2222-2222-222222222221");
    private static final UUID MAPPING_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");

    @BeforeEach
    void setup() {
        savedDeliveries.clear();
        String mockUrl = "http://localhost:9999/webhook";

        ReceiverAdaptor adaptor1 = buildAdaptor(ADAPTOR_ID_1, "Hospital EHR", mockUrl);

        DestinationAdaptorMapping mapping = buildMapping(MAPPING_ID, "sms", ADAPTOR_ID_1, adaptor1);

        when(mappingRepository.findByDestinationAndStatusWithAdaptor("sms", "ACTIVE"))
                .thenReturn(Optional.of(mapping));

        when(deliveryRepository.existsByIntelligenceEventIdAndDestinationAdaptorMappingId(any(), any()))
                .thenReturn(false);

        when(deliveryRepository.findByIntelligenceEventId(any())).thenReturn(List.of());

        when(deliveryRepository.save(any(IntelligenceDelivery.class))).thenAnswer(invocation -> {
            IntelligenceDelivery d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            savedDeliveries.add(d);
            return d;
        });

        when(deliveryRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return savedDeliveries.stream()
                    .filter(d -> id.equals(d.getId()))
                    .reduce((first, second) -> second);
        });

        when(auditLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(webhookDeliveryClient.deliver(any(), any(), any(), any(), any()))
                .thenReturn(WebhookResult.success(200, 1));
    }

    private long countDelivered() {
        return savedDeliveries.stream()
                .filter(d -> d.getStatus() == IntelligenceDeliveryStatus.DELIVERED)
                .map(IntelligenceDelivery::getId)
                .distinct()
                .count();
    }

    private long countFailed() {
        return savedDeliveries.stream()
                .filter(d -> d.getStatus() == IntelligenceDeliveryStatus.FAILED)
                .map(IntelligenceDelivery::getId)
                .distinct()
                .count();
    }

    private IntelligenceDelivery getFirstDelivered() {
        return savedDeliveries.stream()
                .filter(d -> d.getStatus() == IntelligenceDeliveryStatus.DELIVERED)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @Order(1)
    void testOverdueTriggerProducesDelivery() {
        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "MEDIUM", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        assertThat(countDelivered()).isEqualTo(1);

        JsonNode payload = getFirstDelivered().getFhirPayload();
        assertThat(payload.get("resourceType").asText()).isEqualTo("CommunicationRequest");
        assertThat(payload.get("status").asText()).isEqualTo("active");
        assertThat(payload.get("priority").asText()).isEqualTo("urgent");
    }

    @Test
    @Order(2)
    void testCommunicationRequestCriticalPriority() {
        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "CRITICAL", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        assertThat(countDelivered()).isEqualTo(1);
        assertThat(getFirstDelivered().getFhirPayload().get("priority").asText()).isEqualTo("asap");
    }

    @Test
    @Order(3)
    void testTaskTriggerBuildsFhirTask() {
        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "HIGH", "Task", "action-overdue-check");
        consumer.consume(event);

        assertThat(countDelivered()).isEqualTo(1);
        assertThat(getFirstDelivered().getFhirPayload().get("resourceType").asText()).isEqualTo("Task");
    }

    @Test
    @Order(4)
    void testDuplicateTriggerIdempotency() {
        UUID eventId = UUID.randomUUID();
        IntelligenceTriggerEvent event = buildTriggerEvent(eventId, "overdue", "LOW", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        int countAfterFirst = savedDeliveries.size();

        when(deliveryRepository.existsByIntelligenceEventIdAndDestinationAdaptorMappingId(eq(eventId), any()))
                .thenReturn(true);

        consumer.consume(event);
        assertThat(savedDeliveries).hasSize(countAfterFirst);
    }

    @Test
    @Order(5)
    void testWebhookFailureMarksDeliveryFailed() {
        when(webhookDeliveryClient.deliver(any(), any(), any(), any(), any()))
                .thenReturn(WebhookResult.failure(400, "Bad Request", 1));

        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "LOW", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        assertThat(countFailed()).isEqualTo(1);
        assertThat(countDelivered()).isEqualTo(0);
    }

    @Test
    @Order(6)
    void testWebhookRetrySucceeds() {
        when(webhookDeliveryClient.deliver(any(), any(), any(), any(), any()))
                .thenReturn(WebhookResult.success(200, 3));

        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "LOW", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        assertThat(countDelivered()).isEqualTo(1);
        IntelligenceDelivery d = getFirstDelivered();
        assertThat(d.getAttemptCount()).isEqualTo(3);
    }

    @Test
    @Order(7)
    void testDestinationRouting() {
        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "LOW", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        assertThat(countDelivered()).isEqualTo(1);
        verify(mappingRepository).findByDestinationAndStatusWithAdaptor("sms", "ACTIVE");
    }

    @Test
    @Order(8)
    void testLateCompletionTrigger() {
        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "completion", "LOW", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        assertThat(countDelivered()).isEqualTo(1);
    }

    @Test
    @Order(9)
    void testFhirPayloadStructureValidation() {
        IntelligenceTriggerEvent event = buildTriggerEvent(UUID.randomUUID(), "overdue", "HIGH", "CommunicationRequest", "action-overdue-check");
        consumer.consume(event);

        JsonNode payload = getFirstDelivered().getFhirPayload();
        assertThat(payload.get("resourceType").asText()).isEqualTo("CommunicationRequest");
        assertThat(payload.has("identifier")).isTrue();
        assertThat(payload.get("status").asText()).isEqualTo("active");
        assertThat(payload.has("subject")).isTrue();
        assertThat(payload.has("payload")).isTrue();
        assertThat(payload.has("extension")).isTrue();

        JsonNode extensions = payload.get("extension");
        assertThat(extensions.isArray()).isTrue();
        List<String> extUrls = new ArrayList<>();
        extensions.forEach(ext -> extUrls.add(ext.get("url").asText()));
        assertThat(extUrls).contains(
                "http://openphc.org/fhir/StructureDefinition/cce-severity",
                "http://openphc.org/fhir/StructureDefinition/cce-step-status"
        );
    }

    private ReceiverAdaptor buildAdaptor(UUID id, String name, String address) {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("resourceType", "Endpoint");
        definition.put("status", "active");
        definition.put("address", address);
        definition.putObject("connectionType").put("code", "hl7-fhir-rest");

        return ReceiverAdaptor.builder()
                .id(id)
                .name(name)
                .definition(definition)
                .status("ACTIVE")
                .config(null)
                .build();
    }

    private DestinationAdaptorMapping buildMapping(UUID id, String destination, UUID adaptorId, ReceiverAdaptor adaptor) {
        return DestinationAdaptorMapping.builder()
                .id(id)
                .destination(destination)
                .receiverAdaptorId(adaptorId)
                .receiverAdaptor(adaptor)
                .status("ACTIVE")
                .build();
    }

    private IntelligenceTriggerEvent buildTriggerEvent(UUID eventId, String triggerReason, String severity, String actionType, String actionId) {
        IntelligenceTriggerEvent event = new IntelligenceTriggerEvent();
        event.setId(UUID.randomUUID());
        event.setSubject("Patient/test-patient-123");
        event.setIntelligenceEventId(eventId);
        event.setActionDefinitionId(UUID.randomUUID());
        event.setProtocolDefinitionId(UUID.randomUUID());
        event.setActionType(actionType);
        event.setSeverity(severity);
        event.setIntelligenceDestination("sms");
        event.setStepStatus("completion".equals(triggerReason) ? "completed" : "not-started");
        event.setTriggerReason(triggerReason);
        event.setActionId(actionId);
        event.setProtocolCanonical("http://example.org/PlanDefinition/test-protocol");
        event.setDetectedAt(OffsetDateTime.now());
        return event;
    }
}
