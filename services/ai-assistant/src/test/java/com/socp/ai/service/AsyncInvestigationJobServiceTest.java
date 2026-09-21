package com.socp.ai.service;

import com.socp.ai.persistence.entity.InvestigationEntity;
import com.socp.ai.persistence.repository.InvestigationRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AsyncInvestigationJobServiceTest {
    @Test
    void restartedDispatcherRecoversDurableWorkAndBoundsExecutionWithoutAnInMemoryQueue() throws Exception {
        var agent = mock(InvestigationAgentService.class);
        var repository = mock(InvestigationRepository.class);
        var receipt = new InvestigationEntity();
        receipt.setId("job"); receipt.setTenantId("tenant-a"); receipt.setAlertId("alert");
        when(repository.findRecoverable(any(), any())).thenReturn(List.of(receipt));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return java.util.Map.of();
        }).when(agent).investigate("alert");
        var service = new AsyncInvestigationJobService(agent, repository, 1);
        try {
            service.dispatch();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            service.dispatch();
            verify(repository, times(1)).findRecoverable(any(), any());
            verify(agent, times(1)).investigate("alert");
        } finally {
            release.countDown();
            service.close();
        }
    }
}
