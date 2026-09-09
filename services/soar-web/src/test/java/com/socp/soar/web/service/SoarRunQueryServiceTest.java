package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarRunQueryServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void executionQueriesAlwaysUseTheAuthenticatedTenant() {
        SoarRunRepository runs = mock(SoarRunRepository.class);
        when(runs.searchByTenant(eq("tenant-a"), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        SoarRunQueryService query = new SoarRunQueryService(runs,
                mock(SoarDispatchOutboxRepository.class), mock(SoarNodeRunRepository.class),
                mock(SoarRunEventRepository.class), mock(SoarActionAttemptRepository.class),
                mock(SoarManualTaskRepository.class), mock(SoarApprovalRepository.class),
                mock(SoarSignalOutboxRepository.class), new ObjectMapper());
        TenantContext.set("tenant-a");

        assertEquals(0, query.listRuns(PageRequest.of(0, 20), null, null, null, null, null, null)
                .getTotalElements());

        verify(runs).searchByTenant(eq("tenant-a"), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any());
    }
}
