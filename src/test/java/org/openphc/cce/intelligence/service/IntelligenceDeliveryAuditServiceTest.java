package org.openphc.cce.intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.domain.entity.IntelligenceDeliveryAuditLog;
import org.openphc.cce.intelligence.domain.repository.IntelligenceDeliveryAuditLogRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntelligenceDeliveryAuditService}: it persists an audit row with the fixed
 * {@code system} actor, delegates trail lookups, and — importantly — never lets an audit-write
 * failure escape (auditing must not break the delivery pipeline).
 */
@ExtendWith(MockitoExtension.class)
class IntelligenceDeliveryAuditServiceTest {

    @Mock
    private IntelligenceDeliveryAuditLogRepository repository;

    @InjectMocks
    private IntelligenceDeliveryAuditService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void logEvent_savesAuditRowWithSystemActor() {
        UUID deliveryId = UUID.randomUUID();

        service.logEvent(deliveryId, "DELIVERED", mapper.createObjectNode().put("ok", true));

        ArgumentCaptor<IntelligenceDeliveryAuditLog> captor =
                ArgumentCaptor.forClass(IntelligenceDeliveryAuditLog.class);
        verify(repository).save(captor.capture());
        IntelligenceDeliveryAuditLog saved = captor.getValue();
        assertThat(saved.getIntelligenceDeliveryId()).isEqualTo(deliveryId);
        assertThat(saved.getEventType()).isEqualTo("DELIVERED");
        assertThat(saved.getActor()).isEqualTo("system");
        assertThat(saved.getTimestamp()).isNotNull();
    }

    @Test
    void logEvent_whenSaveFails_doesNotPropagate() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.logEvent(UUID.randomUUID(), "CREATED", null))
                .doesNotThrowAnyException();
    }

    @Test
    void getAuditTrail_delegatesToRepository() {
        UUID deliveryId = UUID.randomUUID();

        service.getAuditTrail(deliveryId);

        verify(repository).findByIntelligenceDeliveryIdOrderByTimestampDesc(deliveryId);
    }
}
