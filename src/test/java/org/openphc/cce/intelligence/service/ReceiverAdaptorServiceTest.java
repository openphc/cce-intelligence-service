package org.openphc.cce.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.domain.entity.ReceiverAdaptor;
import org.openphc.cce.intelligence.domain.repository.DestinationAdaptorMappingRepository;
import org.openphc.cce.intelligence.domain.repository.ReceiverAdaptorRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReceiverAdaptorService} — CRUD with FHIR Endpoint-definition validation and
 * the guard against deleting an adaptor that still has ACTIVE destination mappings.
 */
@ExtendWith(MockitoExtension.class)
class ReceiverAdaptorServiceTest {

    @Mock private ReceiverAdaptorRepository adaptorRepository;
    @Mock private DestinationAdaptorMappingRepository mappingRepository;

    @InjectMocks private ReceiverAdaptorService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode validDefinition() {
        return mapper.createObjectNode().put("resourceType", "Endpoint").put("address", "https://x/hook");
    }

    @Test
    void create_withValidDefinition_savesActiveAdaptor() {
        JsonNode def = validDefinition();
        when(adaptorRepository.save(any(ReceiverAdaptor.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create("adaptor-1", def, mapper.createObjectNode());

        ArgumentCaptor<ReceiverAdaptor> captor = ArgumentCaptor.forClass(ReceiverAdaptor.class);
        verify(adaptorRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("adaptor-1");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void create_whenResourceTypeNotEndpoint_throws() {
        JsonNode bad = mapper.createObjectNode().put("resourceType", "Patient").put("address", "https://x");

        assertThatThrownBy(() -> service.create("a", bad, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
        verify(adaptorRepository, never()).save(any());
    }

    @Test
    void create_whenAddressMissing_throws() {
        JsonNode bad = mapper.createObjectNode().put("resourceType", "Endpoint");

        assertThatThrownBy(() -> service.create("a", bad, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");
    }

    @Test
    void update_whenNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(adaptorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, "n", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void update_appliesPartialChanges() {
        UUID id = UUID.randomUUID();
        ReceiverAdaptor existing = ReceiverAdaptor.builder().name("old").status("ACTIVE").build();
        when(adaptorRepository.findById(id)).thenReturn(Optional.of(existing));
        when(adaptorRepository.save(any(ReceiverAdaptor.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(id, "new-name", null, null, "INACTIVE");

        assertThat(existing.getName()).isEqualTo("new-name");
        assertThat(existing.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void delete_whenActiveMappingsExist_throwsIllegalState() {
        UUID id = UUID.randomUUID();
        ReceiverAdaptor adaptor = ReceiverAdaptor.builder().name("a").build();
        when(adaptorRepository.findById(id)).thenReturn(Optional.of(adaptor));
        when(mappingRepository.existsByReceiverAdaptorIdAndStatus(id, "ACTIVE")).thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active");
        verify(adaptorRepository, never()).delete(any());
    }

    @Test
    void delete_whenNoActiveMappings_deletes() {
        UUID id = UUID.randomUUID();
        ReceiverAdaptor adaptor = ReceiverAdaptor.builder().name("a").build();
        when(adaptorRepository.findById(id)).thenReturn(Optional.of(adaptor));
        when(mappingRepository.existsByReceiverAdaptorIdAndStatus(id, "ACTIVE")).thenReturn(false);

        service.delete(id);

        verify(adaptorRepository).delete(adaptor);
    }

    @Test
    void findByStatus_delegates() {
        service.findByStatus("ACTIVE");
        verify(adaptorRepository).findByStatus("ACTIVE");
    }
}
