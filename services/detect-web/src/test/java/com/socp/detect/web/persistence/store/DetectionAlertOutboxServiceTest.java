package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionAlertOutboxServiceTest {

    @Mock
    private DetectionAlertOutboxRepository repository;

    @Test
    void deterministicAlertIdMakesEnqueueIdempotent() {
        when(repository.existsById("alert-1")).thenReturn(true);

        new DetectionAlertOutboxService(repository).enqueue(
                "alert-1", "tenant-a", "{\"id\":\"alert-1\"}");

        verify(repository).existsById("alert-1");
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
}
