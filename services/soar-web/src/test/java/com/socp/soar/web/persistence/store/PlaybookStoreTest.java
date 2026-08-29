package com.socp.soar.web.persistence.store;

import com.socp.soar.web.persistence.repository.PlaybookRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlaybookStoreTest {

    @Test
    void disabledDemoDataDoesNotInspectOrSeedTheDefaultTenant() {
        PlaybookRepository repository = mock(PlaybookRepository.class);

        new PlaybookStore(repository, false);

        verify(repository, never()).countByTenantId("default");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
