package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.domain.PlaybookActionType;
import com.socp.soar.web.temporal.PlaybookActivityImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LegacyPlaybookActionsTest {

    @Mock private PlaybookActionHandlerRegistry registry;
    @Mock private PlaybookActionHandler handler;
    private LegacyPlaybookActions actions;

    @BeforeEach
    void setUp() {
        TenantContext.set("caller");
        actions = new LegacyPlaybookActions(registry, new SoarRuntimeProperties(), null);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void retriesUseTheHistoricalKeyAndTenantAndRestoreTheCaller() {
        registerExecutableHandler();
        given(handler.execute(any())).willAnswer(invocation -> {
            assertThat(TenantContext.require()).isEqualTo("tenant-b");
            return Map.of("status", "failed", "error", "remote failure");
        });
        Map<String, Object> alarm = Map.of("tenantId", "tenant-b", "playbookId", "PB-1", "id", "AL-1");

        Map<String, Object> result = new PlaybookActivityImpl(actions)
                .executeAction("notify security", alarm, false, 2);

        assertThat(result).containsEntry("retried", 2).containsEntry("status", "failed")
                .containsEntry("idempotencyKey", "soar-e0a17630-0022-372a-bb5f-b2bc8d8a9de3");
        ArgumentCaptor<PlaybookActionContext> contexts = ArgumentCaptor.forClass(PlaybookActionContext.class);
        verify(handler, times(3)).execute(contexts.capture());
        assertThat(contexts.getAllValues()).allSatisfy(context -> {
            assertThat(context.idempotencyKey()).isEqualTo(result.get("idempotencyKey"));
            assertThat(context.alarm()).isSameAs(alarm);
        });
        assertThat(TenantContext.require()).isEqualTo("caller");
    }

    @Test
    void successfulRetryStopsAtTheReceiptAndDifferentActionSlotsHaveDifferentKeys() {
        registerExecutableHandler();
        given(handler.execute(any())).willReturn(Map.of("status", "failed"), Map.of("status", "executed"));
        Map<String, Object> alarm = Map.of("tenant_id", "tenant-b", "playbookId", "PB-1", "id", "AL-1");

        Map<String, Object> first = actions.executeAction("notify security", alarm, false, 2);
        verify(handler, times(2)).execute(any());
        Map<String, Object> second = actions.executeAction("notify security", alarm, false, 3);

        assertThat(first).containsEntry("status", "executed").doesNotContainKey("retried");
        assertThat(first.get("idempotencyKey")).isNotEqualTo(second.get("idempotencyKey"));
        assertThat(TenantContext.require()).isEqualTo("caller");
    }

    @Test
    void failedPriorActionSkipsMainActionBeforeHandlerLifecycle() {
        Map<String, Object> result = actions.executeAction("notify security", Map.of(), true, 0);

        assertThat(result).containsEntry("status", "skipped").containsEntry("mode", "NOT_EXECUTED");
        verifyNoInteractions(registry, handler);
    }

    @Test
    void compensationStillRunsAfterFailure() {
        registerExecutableHandler();
        given(handler.execute(any())).willReturn(Map.of("status", "executed"));

        Map<String, Object> result = actions.executeAction("补偿:notify rollback", Map.of(), true, 1);

        assertThat(result).containsEntry("status", "executed");
        verify(handler).execute(any());
    }

    @Test
    void invalidCarriedTenantFailsBeforeAnyConnectorAndRetainsCaller() {
        assertThatThrownBy(() -> actions.executeAction("notify", Map.of("tenantId", "../invalid"), false))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(registry, handler);
        assertThat(TenantContext.require()).isEqualTo("caller");
    }

    @Test
    void lifecycleApprovalFailureCannotInvokeRemoteAction() {
        given(registry.find(PlaybookActionType.NOTIFY)).willReturn(handler);
        given(handler.validate(any())).willReturn(Map.of("status", "validated"));
        given(handler.prepare(any())).willReturn(Map.of("status", "prepared"));
        given(handler.requestApproval(any())).willReturn(Map.of("status", "approval_required"));

        Map<String, Object> result = actions.executeAction("notify", Map.of(), false);

        assertThat(result).containsEntry("status", "failed")
                .containsEntry("errorCode", "CONNECTOR_APPROVAL_REQUIRED");
        verify(handler, never()).execute(any());
    }

    private void registerExecutableHandler() {
        given(registry.find(PlaybookActionType.NOTIFY)).willReturn(handler);
        given(handler.validate(any())).willReturn(Map.of("status", "validated"));
        given(handler.prepare(any())).willReturn(Map.of("status", "prepared"));
        given(handler.requestApproval(any())).willReturn(Map.of("status", "approval_not_required"));
    }
}
