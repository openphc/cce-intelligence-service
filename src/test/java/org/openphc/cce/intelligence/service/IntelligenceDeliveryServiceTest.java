package org.openphc.cce.intelligence.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.domain.entity.IntelligenceDelivery;
import org.openphc.cce.intelligence.domain.enums.IntelligenceDeliveryStatus;
import org.openphc.cce.intelligence.domain.repository.IntelligenceDeliveryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntelligenceDeliveryService} — query delegation, dynamic filtering, and the
 * cancel state-machine guard (only PENDING/FAILED are cancellable).
 */
@ExtendWith(MockitoExtension.class)
class IntelligenceDeliveryServiceTest {

    @Mock private IntelligenceDeliveryRepository deliveryRepository;
    @Mock private IntelligenceDeliveryAuditService auditService;

    @InjectMocks private IntelligenceDeliveryService service;

    private final Pageable pageable = PageRequest.of(0, 10);

    @Test
    void findByStatus_delegates() {
        Page<IntelligenceDelivery> page = new PageImpl<>(List.of());
        when(deliveryRepository.findByStatus(IntelligenceDeliveryStatus.DELIVERED, pageable)).thenReturn(page);

        assertThat(service.findByStatus(IntelligenceDeliveryStatus.DELIVERED, pageable)).isSameAs(page);
    }

    @Test
    void findFiltered_withCriteria_queriesBySpecification() {
        Page<IntelligenceDelivery> page = new PageImpl<>(List.of());
        when(deliveryRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<IntelligenceDelivery> result = service.findFiltered(
                "delivered", "Patient/1", null, null, null, "high", null, null, pageable);

        assertThat(result).isSameAs(page);
        verify(deliveryRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void findAll_findBySubject_findById_delegate() {
        Page<IntelligenceDelivery> page = new PageImpl<>(List.of());
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findAll(pageable)).thenReturn(page);
        when(deliveryRepository.findBySubject("Patient/1", pageable)).thenReturn(page);
        when(deliveryRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findAll(pageable)).isSameAs(page);
        assertThat(service.findBySubject("Patient/1", pageable)).isSameAs(page);
        assertThat(service.findById(id)).isEmpty();
    }

    @Test
    void findFiltered_withAllCriteria_buildsSpecificationWithoutError() {
        Page<IntelligenceDelivery> page = new PageImpl<>(List.of());
        when(deliveryRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<IntelligenceDelivery> result = service.findFiltered(
                "pending", "Patient/1", UUID.randomUUID(), UUID.randomUUID(),
                "CommunicationRequest", "critical", "dest-a", UUID.randomUUID(), pageable);

        assertThat(result).isSameAs(page);
    }

    @Test
    void cancel_whenNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancel_whenDelivered_throwsIllegalState() {
        UUID id = UUID.randomUUID();
        IntelligenceDelivery delivery = IntelligenceDelivery.builder()
                .status(IntelligenceDeliveryStatus.DELIVERED).build();
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.cancel(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING or FAILED");
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void cancel_whenPending_setsCancelledAndAudits() {
        UUID id = UUID.randomUUID();
        IntelligenceDelivery delivery = IntelligenceDelivery.builder()
                .status(IntelligenceDeliveryStatus.PENDING).build();
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(delivery)).thenReturn(delivery);

        service.cancel(id);

        assertThat(delivery.getStatus()).isEqualTo(IntelligenceDeliveryStatus.CANCELLED);
        verify(auditService).logEvent(id, "CANCELLED", null);
    }
}
