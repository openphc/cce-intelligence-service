package org.openphc.cce.intelligence.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.intelligence.engine.IntelligenceEngine;
import org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IntelligenceTriggerConsumer {

    private final IntelligenceEngine intelligenceEngine;
    private final MeterRegistry meterRegistry;
    private final Counter consumerErrorsCounter;

    public IntelligenceTriggerConsumer(IntelligenceEngine intelligenceEngine, MeterRegistry meterRegistry) {
        this.intelligenceEngine = intelligenceEngine;
        this.meterRegistry = meterRegistry;
        this.consumerErrorsCounter = Counter.builder("cce.intelligence.consumer.errors")
                .description("Intelligence consumer processing errors")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${cce.kafka.topics.intelligence-triggers}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {
                    "spring.json.value.default.type=org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent"
            }
    )
    public void consume(IntelligenceTriggerEvent event) {
        MDC.put("correlationId", event.getId().toString());
        MDC.put("subject", event.getSubject());
        MDC.put("intelligenceEventId", event.getIntelligenceEventId().toString());
        MDC.put("stepStatus", event.getStepStatus());

        try {
            log.info("Received intelligence trigger: eventId={}, subject={}, stepStatus={}, triggerReason={}, "
                            + "intelligenceActionId={}, destination={}",
                    event.getIntelligenceEventId(), event.getSubject(), event.getStepStatus(),
                    event.getTriggerReason(), event.getIntelligenceActionId(), event.getIntelligenceDestination());

            String triggerType = deriveTriggerType(event);
            Counter.builder("cce.intelligence.triggers.received")
                    .description("Intelligence trigger events received")
                    .tag("trigger_type", triggerType)
                    .register(meterRegistry)
                    .increment();

            intelligenceEngine.processTrigger(event);

            log.info("Trigger processed successfully: eventId={}", event.getIntelligenceEventId());
        } catch (Exception ex) {
            consumerErrorsCounter.increment();
            log.error("Error processing intelligence trigger: eventId={}, error={}",
                    event.getIntelligenceEventId(), ex.getMessage(), ex);
            throw ex;
        } finally {
            MDC.clear();
        }
    }

    private String deriveTriggerType(IntelligenceTriggerEvent event) {
        String triggerReason = event.getTriggerReason();
        if (triggerReason == null) return "unknown";
        return switch (triggerReason) {
            case "completion" -> "step.completed";
            case "overdue" -> "deviation.overdue";
            case "missed" -> "deviation.missed";
            case "order_violation" -> "deviation.order_violation";
            default -> "unknown";
        };
    }
}
