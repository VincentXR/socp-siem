package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarApprovalExpiryWorkerCoverageTest {

    @Mock
    private SoarApprovalRepository approvals;
    @Mock
    private SoarService soar;

    private SoarApprovalExpiryWorker worker;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        worker = new SoarApprovalExpiryWorker(approvals, soar);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pendingApprovalPastExpiryIsExpiredInItsOwnTenant() {
        AtomicReference<String> seenTenant = new AtomicReference<>();
        given(approvals.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(approval("apr-1", "tenant-b", Instant.now().minusSeconds(60))));
        given(soar.expireApproval(anyString(), any())).willAnswer(invocation -> {
            seenTenant.set(TenantContext.get());
            return true;
        });

        worker.tick();

        verify(soar).expireApproval(eq("apr-1"), any(Instant.class));
        assertThat(seenTenant.get()).isEqualTo("tenant-b");
        assertThat(TenantContext.get()).isEqualTo("tenant-a");
    }

    @Test
    void severalExpiredApprovalsAreAllEnqueued() {
        given(approvals.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(
                        approval("apr-1", "tenant-a", Instant.now().minusSeconds(60)),
                        approval("apr-2", "tenant-c", Instant.now().minusSeconds(30))));

        worker.tick();

        verify(soar).expireApproval(eq("apr-1"), any(Instant.class));
        verify(soar).expireApproval(eq("apr-2"), any(Instant.class));
    }

    @Test
    void approvalWithoutTenantIsSkipped() {
        given(approvals.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(approval("apr-3", "   ", Instant.now().minusSeconds(60))));

        worker.tick();

        verify(soar, never()).expireApproval(anyString(), any());
    }

    @Test
    void nothingExpiredLeavesTheJanitorIdle() {
        given(approvals.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq("PENDING"), any()))
                .willReturn(List.of());

        worker.tick();

        verify(soar, never()).expireApproval(anyString(), any());
    }

    @Test
    void scanUsesPendingStatusAndCurrentTimeCutoff() {
        org.mockito.ArgumentCaptor<Instant> cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        given(approvals.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq("PENDING"), any()))
                .willReturn(List.of());

        Instant before = Instant.now().minusSeconds(5);
        worker.tick();
        Instant after = Instant.now().plusSeconds(5);

        verify(approvals).findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq("PENDING"), cutoff.capture());
        assertThat(cutoff.getValue().isAfter(before)).isTrue();
        assertThat(cutoff.getValue().isBefore(after)).isTrue();
    }

    private static SoarApprovalEntity approval(String id, String tenantId, Instant expiresAt) {
        SoarApprovalEntity approval = new SoarApprovalEntity();
        approval.setId(id);
        approval.setTenantId(tenantId);
        approval.setRunId("run-1");
        approval.setApprovalKey("gate-1");
        approval.setStatus("PENDING");
        approval.setRequestedBy("operator");
        approval.setCreatedAt(Instant.now().minusSeconds(120));
        approval.setExpiresAt(expiresAt);
        return approval;
    }
}
