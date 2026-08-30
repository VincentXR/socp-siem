package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.persistence.entity.ApprovalEntity;
import com.socp.soar.web.persistence.repository.ApprovalRepository;
import com.socp.soar.web.persistence.store.PlaybookStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ApprovalRepository repository;
    @Mock
    private PlaybookStore playbookStore;
    @Mock
    private PlaybookExecutor executor;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new ApprovalService(repository, playbookStore, executor);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void highRiskPolicyRequiresApprovalButSimulationDoesNot() {
        Playbook firewall = Playbook.create("firewall", "manual", List.of("firewall-block"), true);
        Playbook simulation = Playbook.create("demo", "manual", List.of("simulate:firewall-block"), true);
        given(playbookStore.get(firewall.id())).willReturn(firewall);
        given(playbookStore.get(simulation.id())).willReturn(simulation);

        assertThat(service.requiresApproval(firewall.id())).isTrue();
        assertThat(service.requiresApproval(simulation.id())).isFalse();
    }

    @Test
    void requestPersistsTenantBoundPendingApproval() {
        Playbook firewall = Playbook.create("firewall", "manual", List.of("firewall-block"), true);
        given(playbookStore.get(firewall.id())).willReturn(firewall);
        given(repository.save(any(ApprovalEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> view = service.request(firewall.id(), Map.of("host", "web-1"), "alice", "contain host");

        ArgumentCaptor<ApprovalEntity> captor = ArgumentCaptor.forClass(ApprovalEntity.class);
        verify(repository).save(captor.capture());
        ApprovalEntity saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getScopeJson()).contains("web-1");
        assertThat(view).containsEntry("status", "PENDING");
    }

    @Test
    void expiredApprovalCannotBeApproved() {
        ApprovalEntity entity = entity("APR-1", "PENDING", Instant.now().minusSeconds(1));
        given(repository.findByApprovalIdAndTenantId("APR-1", "tenant-a")).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.approve("APR-1", "admin", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
        assertThat(entity.getStatus()).isEqualTo("EXPIRED");
        verify(repository).save(entity);
    }

    @Test
    void executeUsesApprovedEntryPointAndStableApprovalIdentity() {
        ApprovalEntity entity = entity("APR-2", "APPROVED", Instant.now().plusSeconds(300));
        entity.setScopeJson("{\"host\":\"web-2\"}");
        given(repository.findByApprovalIdAndTenantId("APR-2", "tenant-a")).willReturn(Optional.of(entity));
        given(executor.runApprovedById(any(String.class), any(Map.class)))
                .willReturn(Map.of("executionId", "EXEC-2", "status", "SUCCESS"));

        Map<String, Object> result = service.execute("APR-2");

        assertThat(result).containsEntry("executionId", "EXEC-2");
        ArgumentCaptor<Map<String, Object>> scope = ArgumentCaptor.forClass(Map.class);
        verify(executor).runApprovedById(org.mockito.ArgumentMatchers.eq(entity.getPlaybookId()), scope.capture());
        assertThat(scope.getValue()).containsEntry("approvalId", "APR-2")
                .containsEntry("id", "approval-APR-2");
        assertThat(entity.getStatus()).isEqualTo("EXECUTED");
        assertThat(entity.getExecutionId()).isEqualTo("EXEC-2");
    }

    @Test
    void failedExecutionIsRecordedAndNotReportedAsExecuted() {
        ApprovalEntity entity = entity("APR-3", "APPROVED", Instant.now().plusSeconds(300));
        given(repository.findByApprovalIdAndTenantId("APR-3", "tenant-a")).willReturn(Optional.of(entity));
        given(executor.runApprovedById(any(String.class), any(Map.class)))
                .willReturn(Map.of("executionId", "EXEC-3", "status", "FAILED"));

        service.execute("APR-3");

        assertThat(entity.getStatus()).isEqualTo("EXECUTION_FAILED");
        verify(repository).save(entity);
        verify(executor, never()).runById(any(String.class), any(Map.class));
    }

    private static ApprovalEntity entity(String id, String status, Instant expiresAt) {
        ApprovalEntity entity = new ApprovalEntity();
        entity.setApprovalId(id);
        entity.setTenantId("tenant-a");
        entity.setPlaybookId("pb-1");
        entity.setStatus(status);
        entity.setExpiresAt(expiresAt);
        entity.setScopeJson("{}");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
