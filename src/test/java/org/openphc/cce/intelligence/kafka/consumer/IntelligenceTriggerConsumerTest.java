package org.openphc.cce.intelligence.kafka.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.engine.IntelligenceEngine;
import org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link IntelligenceTriggerConsumer}: it forwards events to the engine, records a
 * received-trigger metric tagged by the derived trigger type, and counts/rethrows on failure.
 */
@ExtendWith(MockitoExtension.class)
class IntelligenceTriggerConsumerTest {

    @Mock
    private IntelligenceEngine engine;

    private SimpleMeterRegistry meterRegistry;
    private IntelligenceTriggerConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new IntelligenceTriggerConsumer(engine, meterRegistry);
    }

    private IntelligenceTriggerEvent event(String stepState) {
        return IntelligenceTriggerEvent.builder()
                .id(UUID.randomUUID())
                .subject("Patient/1")
                .intelligenceEventId(UUID.randomUUID())
                .intelligenceDestination("dest-a")
                .stepState(stepState)
                .build();
    }

    @Test
    void consume_forwardsToEngineAndCountsReceived() {
        IntelligenceTriggerEvent event = event("due");

        consumer.consume(event);

        verify(engine).processTrigger(event);
        double count = meterRegistry.counter("cce.intelligence.triggers.received", "trigger_type", "step.due")
                .count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void consume_derivesTriggerTypeFromStepState() {
        consumer.consume(event("overdue"));
        consumer.consume(event("missed"));
        consumer.consume(event("completed"));
        consumer.consume(event(null));

        assertThat(meterRegistry.counter("cce.intelligence.triggers.received", "trigger_type", "deviation.overdue").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("cce.intelligence.triggers.received", "trigger_type", "deviation.missed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("cce.intelligence.triggers.received", "trigger_type", "step.completed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("cce.intelligence.triggers.received", "trigger_type", "unknown").count()).isEqualTo(1.0);
    }

    @Test
    void consume_whenEngineThrows_countsErrorAndRethrows() {
        IntelligenceTriggerEvent event = event("due");
        doThrow(new RuntimeException("engine failure")).when(engine).processTrigger(event);

        assertThatThrownBy(() -> consumer.consume(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("engine failure");

        assertThat(meterRegistry.counter("cce.intelligence.consumer.errors").count()).isEqualTo(1.0);
    }
}
