package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class SoarV2SignalWorkerCoverageTest {

    @Mock
    private SoarSignalOutboxRepository signals;
    @Mock
    private SoarRunRepository runs;
    @Mock
    private TemporalExecutor temporal;

    private SoarV2SignalWorker worker;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        worker = new SoarV2SignalWorker(signals, runs, temporal, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void approvalSignalWithGateKeyIsDeliveredThroughTheGateDecision() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-1",
                "{\"approve\":true,\"approvalKey\":\"gate-1\"}", 0);
        givenPending(signal, run("WAITING_APPROVAL", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).decideGateV2("soar-v2-tenant-a-run-1", true, "gate-1", false);
        assertThat(signal.getStatus()).isEqualTo("SENT");
        verify(signals).save(signal);
    }

    @Test
    void expiredApprovalSignalUsesTheExpireGatePath() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-2",
                "{\"approve\":false,\"approvalKey\":\"gate-2\",\"expired\":true}", 0);
        givenPending(signal, run("WAITING_APPROVAL", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).decideGateV2("soar-v2-tenant-a-run-1", false, "gate-2", true);
        assertThat(signal.getStatus()).isEqualTo("SENT");
    }

    @Test
    void legacyApprovalSignalWithoutGateKeyUsesPlainDecision() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "", "{\"approve\":true}", 0);
        givenPending(signal, run("WAITING_APPROVAL", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).decideV2("soar-v2-tenant-a-run-1", true);
        assertThat(signal.getStatus()).isEqualTo("SENT");
    }

    @Test
    void manualTaskSignalWithNodeIdTargetsTheNode() {
        SoarSignalOutboxEntity signal = signal("MANUAL_TASK", "node-1",
                "{\"nodeId\":\"node-1\",\"input\":{\"ticket\":\"T-1\"}}", 0);
        givenPending(signal, run("RUNNING", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).completeManualTaskForNode("soar-v2-tenant-a-run-1", "node-1", "{\"ticket\":\"T-1\"}");
        assertThat(signal.getStatus()).isEqualTo("SENT");
    }

    @Test
    void manualTaskSignalWithoutNodeIdUsesTheLegacyEntryPoint() {
        SoarSignalOutboxEntity signal = signal("MANUAL_TASK", "", "{}", 0);
        givenPending(signal, run("RUNNING", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).completeManualTask("soar-v2-tenant-a-run-1", "{}");
        assertThat(signal.getStatus()).isEqualTo("SENT");
    }

    @Test
    void unknownResolutionSignalForwardsEvidenceAndReason() {
        SoarSignalOutboxEntity signal = signal("UNKNOWN_RESOLUTION", "node-9",
                "{\"nodeId\":\"node-9\",\"resolution\":\"SUCCEEDED\",\"evidence\":\"ev\",\"reason\":\"why\"}", 0);
        givenPending(signal, run("RUNNING", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).resolveUnknown("soar-v2-tenant-a-run-1", "node-9", "SUCCEEDED", "ev", "why");
        assertThat(signal.getStatus()).isEqualTo("SENT");
    }

    @Test
    void unknownResolutionSignalCanResumeAnActionUnknownRun() {
        SoarSignalOutboxEntity signal = signal("UNKNOWN_RESOLUTION", "node-unknown",
                "{\"nodeId\":\"node-unknown\",\"resolution\":\"SUCCEEDED\",\"evidence\":\"receipt\",\"reason\":\"verified\"}", 0);
        givenPending(signal, run("ACTION_UNKNOWN", "soar-v2-tenant-a-run-1"));

        worker.tick();

        verify(temporal).resolveUnknown("soar-v2-tenant-a-run-1", "node-unknown",
                "SUCCEEDED", "receipt", "verified");
        assertThat(signal.getStatus()).isEqualTo("SENT");
    }

    @Test
    void lateSignalForTerminalRunIsCancelledWithoutTouchingTemporal() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-terminal",
                "{\"approve\":true,\"approvalKey\":\"gate-terminal\"}", 0);
        givenPending(signal, run("PARTIALLY_SUCCEEDED", "soar-v2-tenant-a-run-1"));

        worker.tick();

        assertThat(signal.getStatus()).isEqualTo("CANCELLED");
        assertThat(signal.getLastError()).contains("PARTIALLY_SUCCEEDED");
        verify(signals).save(signal);
        verify(temporal).isAvailable();
        verifyNoMoreInteractions(temporal);
    }

    @Test
    void signalForCancellingRunIsCancelledWithoutTouchingTemporal() {
        SoarSignalOutboxEntity signal = signal("MANUAL_TASK", "node-cancelling", "{}", 0);
        givenPending(signal, run("CANCELLING", "soar-v2-tenant-a-run-1"));

        worker.tick();

        assertThat(signal.getStatus()).isEqualTo("CANCELLED");
        verify(signals).save(signal);
        verify(temporal).isAvailable();
        verifyNoMoreInteractions(temporal);
    }

    @Test
    void terminalSignalWithoutWorkflowIdIsCancelledWithoutTouchingTemporal() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-terminal-no-workflow", "{\"approve\":true}", 0);
        givenPending(signal, run("SUCCEEDED", "  "));

        worker.tick();

        assertThat(signal.getStatus()).isEqualTo("CANCELLED");
        verify(signals).save(signal);
        verify(temporal).isAvailable();
        verifyNoMoreInteractions(temporal);
    }

    @Test
    void signalBeforeDispatchIsClosedWithoutTouchingTemporal() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-1",
                "{\"approve\":true,\"approvalKey\":\"gate-1\"}", 0);
        givenPending(signal, run("WAITING_APPROVAL", "  "));

        worker.tick();

        assertThat(signal.getStatus()).isEqualTo("SENT");
        verify(temporal).isAvailable();
        verifyNoMoreInteractions(temporal);
    }

    @Test
    void signalForMissingRunIsClosedWithoutTouchingTemporal() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-1", "{\"approve\":true}", 0);
        given(temporal.isAvailable()).willReturn(true);
        given(signals.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(signal));
        given(signals.claim(eq("tenant-a"), eq("sig-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.empty());

        worker.tick();

        assertThat(signal.getStatus()).isEqualTo("SENT");
        verify(temporal).isAvailable();
        verifyNoMoreInteractions(temporal);
    }

    @Test
    void deliveryFailureRequeuesWithBackoff() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-1",
                "{\"approve\":true,\"approvalKey\":\"gate-1\"}", 0);
        givenPending(signal, run("WAITING_APPROVAL", "soar-v2-tenant-a-run-1"));
        doThrow(new IllegalStateException("temporal signal failed"))
                .when(temporal).decideGateV2(anyString(), anyBoolean(), anyString(), anyBoolean());

        worker.tick();

        assertThat(signal.getAttempts()).isEqualTo(1);
        assertThat(signal.getStatus()).isEqualTo("PENDING");
        assertThat(signal.getLastError()).isEqualTo("temporal signal failed");
        assertThat(signal.getNextAttemptAt()).isAfter(Instant.now());
        verify(signals).save(signal);
    }

    @Test
    void exhaustedBudgetMovesSignalToDead() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-1",
                "{\"approve\":true,\"approvalKey\":\"gate-1\"}", 9);
        givenPending(signal, run("WAITING_APPROVAL", "soar-v2-tenant-a-run-1"));
        doThrow(new IllegalStateException("temporal signal failed"))
                .when(temporal).decideGateV2(anyString(), anyBoolean(), anyString(), anyBoolean());

        worker.tick();

        assertThat(signal.getAttempts()).isEqualTo(10);
        assertThat(signal.getStatus()).isEqualTo("DEAD");
        assertThat(signal.getLastError()).isEqualTo("temporal signal failed");
    }

    @Test
    void unclaimedSignalIsLeftForAnotherWorker() {
        SoarSignalOutboxEntity signal = signal("APPROVAL", "gate-1", "{\"approve\":true}", 0);
        given(temporal.isAvailable()).willReturn(true);
        given(signals.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(signal));
        given(signals.claim(eq("tenant-a"), eq("sig-1"), anyString(), any())).willReturn(0);

        worker.tick();

        assertThat(signal.getStatus()).isEqualTo("PENDING");
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void unavailableTemporalShortCircuitsThePoll() {
        given(temporal.isAvailable()).willReturn(false);

        worker.tick();

        verify(signals, never())
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(anyString(), any());
        verify(signals, never()).claim(anyString(), anyString(), anyString(), any());
    }

    private void givenPending(SoarSignalOutboxEntity signal, SoarRunEntity run) {
        given(temporal.isAvailable()).willReturn(true);
        given(signals.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(signal));
        given(signals.claim(eq("tenant-a"), eq("sig-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
    }

    private static SoarSignalOutboxEntity signal(String type, String key, String payload, int attempts) {
        SoarSignalOutboxEntity signal = new SoarSignalOutboxEntity();
        signal.setId("sig-1");
        signal.setTenantId("tenant-a");
        signal.setRunId("run-1");
        signal.setSignalType(type);
        signal.setSignalKey(key);
        signal.setPayloadJson(payload);
        signal.setStatus("PENDING");
        signal.setAttempts(attempts);
        signal.setNextAttemptAt(Instant.now().minusSeconds(1));
        signal.setCreatedAt(Instant.now().minusSeconds(5));
        signal.setUpdatedAt(Instant.now().minusSeconds(5));
        return signal;
    }

    private static SoarRunEntity run(String status, String workflowId) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId("run-1");
        run.setTenantId("tenant-a");
        run.setStatus(status);
        run.setTemporalWorkflowId(workflowId);
        run.setUpdatedAt(Instant.now());
        return run;
    }
}
