package org.openphc.cce.intelligence.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.domain.entity.DestinationAdaptorMapping;
import org.openphc.cce.intelligence.domain.repository.IntelligenceDeliveryRepository;
import org.openphc.cce.intelligence.kafka.model.IntelligenceTriggerEvent;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntelligenceEngine#processTrigger} orchestration: destination resolution,
 * idempotency short-circuit, dispatch, and dispatch-failure containment.
 */
@ExtendWith(MockitoExtension.class)
class IntelligenceEngineTest {

    @Mock private DestinationRouter destinationRouter;
    @Mock private FhirPayloadBuilder fhirPayloadBuilder;
    @Mock private ActionDispatcher actionDispatcher;
    @Mock private IntelligenceDeliveryRepository deliveryRepository;

    private IntelligenceEngine engine;

    private final UUID eventId = UUID.randomUUID();
    private final UUID mappingId = UUID.randomUUID();
    private IntelligenceTriggerEvent event;

    @BeforeEach
    void setUp() {
        engine = new IntelligenceEngine(
                destinationRouter, fhirPayloadBuilder, actionDispatcher, deliveryRepository);
        event = IntelligenceTriggerEvent.builder()
                .intelligenceEventId(eventId)
                .intelligenceDestination("dest-a")
                .build();
    }

    @Test
    void processTrigger_whenNoMapping_doesNotDispatch() {
        when(destinationRouter.resolveAdaptor("dest-a")).thenReturn(Optional.empty());

        engine.processTrigger(event);

        verifyNoInteractions(actionDispatcher);
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void processTrigger_whenDeliveryAlreadyExists_doesNotDispatch() {
        DestinationAdaptorMapping mapping = mock(DestinationAdaptorMapping.class);
        when(mapping.getId()).thenReturn(mappingId);
        when(destinationRouter.resolveAdaptor("dest-a")).thenReturn(Optional.of(mapping));
        when(deliveryRepository.existsByIntelligenceEventIdAndDestinationAdaptorMappingId(eventId, mappingId))
                .thenReturn(true);

        engine.processTrigger(event);

        verifyNoInteractions(actionDispatcher);
    }

    @Test
    void processTrigger_whenNewDelivery_dispatchesOnce() {
        DestinationAdaptorMapping mapping = mock(DestinationAdaptorMapping.class);
        when(mapping.getId()).thenReturn(mappingId);
        when(destinationRouter.resolveAdaptor("dest-a")).thenReturn(Optional.of(mapping));
        when(deliveryRepository.existsByIntelligenceEventIdAndDestinationAdaptorMappingId(eventId, mappingId))
                .thenReturn(false);

        engine.processTrigger(event);

        verify(actionDispatcher).dispatch(eq(event), eq(mapping));
    }

    @Test
    void processTrigger_whenDispatchThrows_isContained() {
        DestinationAdaptorMapping mapping = mock(DestinationAdaptorMapping.class);
        when(mapping.getId()).thenReturn(mappingId);
        when(destinationRouter.resolveAdaptor("dest-a")).thenReturn(Optional.of(mapping));
        when(deliveryRepository.existsByIntelligenceEventIdAndDestinationAdaptorMappingId(eventId, mappingId))
                .thenReturn(false);
        doThrow(new RuntimeException("boom")).when(actionDispatcher).dispatch(any(), any());

        // Must not propagate — the consumer relies on the engine swallowing dispatch failures here.
        engine.processTrigger(event);

        verify(actionDispatcher).dispatch(eq(event), eq(mapping));
    }
}
