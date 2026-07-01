package org.openphc.cce.intelligence.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.domain.entity.DestinationAdaptorMapping;
import org.openphc.cce.intelligence.domain.enums.IntelligenceDeliveryStatus;
import org.openphc.cce.intelligence.domain.repository.DestinationAdaptorMappingRepository;
import org.openphc.cce.intelligence.domain.repository.IntelligenceDeliveryRepository;
import org.openphc.cce.intelligence.domain.repository.ReceiverAdaptorRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DestinationAdaptorMappingService} — 1:1 destination mapping creation with
 * uniqueness + adaptor-existence guards, partial update, and the active-delivery deletion guard.
 */
@ExtendWith(MockitoExtension.class)
class DestinationAdaptorMappingServiceTest {

    @Mock private DestinationAdaptorMappingRepository mappingRepository;
    @Mock private ReceiverAdaptorRepository receiverAdaptorRepository;
    @Mock private IntelligenceDeliveryRepository deliveryRepository;

    @InjectMocks private DestinationAdaptorMappingService service;

    @Test
    void findFiltered_whenAllNull_returnsFindAll() {
        when(mappingRepository.findAll()).thenReturn(List.of());

        service.findFiltered(null, null, null);

        verify(mappingRepository).findAll();
        verify(mappingRepository, never()).findByFilters(any(), any(), any());
    }

    @Test
    void findFiltered_withCriteria_usesFindByFilters() {
        UUID adaptorId = UUID.randomUUID();
        service.findFiltered("dest-a", adaptorId, "ACTIVE");

        verify(mappingRepository).findByFilters("dest-a", adaptorId, "ACTIVE");
    }

    @Test
    void create_whenAdaptorMissing_throws() {
        UUID adaptorId = UUID.randomUUID();
        when(receiverAdaptorRepository.existsById(adaptorId)).thenReturn(false);

        assertThatThrownBy(() -> service.create("dest-a", adaptorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Receiver adaptor not found");
    }

    @Test
    void create_whenDestinationAlreadyMapped_throws() {
        UUID adaptorId = UUID.randomUUID();
        when(receiverAdaptorRepository.existsById(adaptorId)).thenReturn(true);
        when(mappingRepository.existsByDestination("dest-a")).thenReturn(true);

        assertThatThrownBy(() -> service.create("dest-a", adaptorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already mapped");
    }

    @Test
    void create_whenValid_savesActiveMapping() {
        UUID adaptorId = UUID.randomUUID();
        when(receiverAdaptorRepository.existsById(adaptorId)).thenReturn(true);
        when(mappingRepository.existsByDestination("dest-a")).thenReturn(false);
        when(mappingRepository.save(any(DestinationAdaptorMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create("dest-a", adaptorId);

        ArgumentCaptor<DestinationAdaptorMapping> captor =
                ArgumentCaptor.forClass(DestinationAdaptorMapping.class);
        verify(mappingRepository).save(captor.capture());
        assertThat(captor.getValue().getDestination()).isEqualTo("dest-a");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void findAll_and_findById_delegate() {
        UUID id = UUID.randomUUID();
        when(mappingRepository.findAll()).thenReturn(List.of());
        when(mappingRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findAll()).isEmpty();
        assertThat(service.findById(id)).isEmpty();
    }

    @Test
    void update_whenNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(mappingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, null, "INACTIVE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void update_whenReceiverAdaptorMissing_throws() {
        UUID id = UUID.randomUUID();
        UUID adaptorId = UUID.randomUUID();
        DestinationAdaptorMapping mapping = DestinationAdaptorMapping.builder().destination("d").build();
        when(mappingRepository.findById(id)).thenReturn(Optional.of(mapping));
        when(receiverAdaptorRepository.existsById(adaptorId)).thenReturn(false);

        assertThatThrownBy(() -> service.update(id, adaptorId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Receiver adaptor not found");
    }

    @Test
    void update_appliesPartialChanges() {
        UUID id = UUID.randomUUID();
        UUID adaptorId = UUID.randomUUID();
        DestinationAdaptorMapping mapping = DestinationAdaptorMapping.builder().destination("d").status("ACTIVE").build();
        when(mappingRepository.findById(id)).thenReturn(Optional.of(mapping));
        when(receiverAdaptorRepository.existsById(adaptorId)).thenReturn(true);
        when(mappingRepository.save(mapping)).thenReturn(mapping);

        service.update(id, adaptorId, "INACTIVE");

        assertThat(mapping.getReceiverAdaptorId()).isEqualTo(adaptorId);
        assertThat(mapping.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void delete_whenActiveDeliveries_throwsIllegalState() {
        UUID id = UUID.randomUUID();
        DestinationAdaptorMapping mapping = DestinationAdaptorMapping.builder().destination("d").build();
        when(mappingRepository.findById(id)).thenReturn(Optional.of(mapping));
        when(deliveryRepository.existsByDestinationAdaptorMappingIdAndStatusIn(eq(id), anyList()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active");
        verify(mappingRepository, never()).delete(any());
    }

    @Test
    void delete_whenNoActiveDeliveries_deletes() {
        UUID id = UUID.randomUUID();
        DestinationAdaptorMapping mapping = DestinationAdaptorMapping.builder().destination("d").build();
        when(mappingRepository.findById(id)).thenReturn(Optional.of(mapping));
        when(deliveryRepository.existsByDestinationAdaptorMappingIdAndStatusIn(eq(id), anyList()))
                .thenReturn(false);

        service.delete(id);

        verify(mappingRepository).delete(mapping);
    }
}
