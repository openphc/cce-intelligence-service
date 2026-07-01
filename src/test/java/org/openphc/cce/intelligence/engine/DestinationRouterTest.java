package org.openphc.cce.intelligence.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.intelligence.domain.entity.DestinationAdaptorMapping;
import org.openphc.cce.intelligence.domain.repository.DestinationAdaptorMappingRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DestinationRouter}, which resolves the single ACTIVE adaptor mapping for a
 * destination.
 */
@ExtendWith(MockitoExtension.class)
class DestinationRouterTest {

    @Mock
    private DestinationAdaptorMappingRepository repository;

    @InjectMocks
    private DestinationRouter router;

    @Test
    void resolveAdaptor_whenActiveMappingExists_returnsIt() {
        DestinationAdaptorMapping mapping = mock(DestinationAdaptorMapping.class);
        when(repository.findByDestinationAndStatusWithAdaptor("dest-a", "ACTIVE"))
                .thenReturn(Optional.of(mapping));

        Optional<DestinationAdaptorMapping> result = router.resolveAdaptor("dest-a");

        assertThat(result).containsSame(mapping);
    }

    @Test
    void resolveAdaptor_whenNoActiveMapping_returnsEmpty() {
        when(repository.findByDestinationAndStatusWithAdaptor("dest-b", "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThat(router.resolveAdaptor("dest-b")).isEmpty();
    }
}
