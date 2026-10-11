package com.socp.soar.web.temporal;

import com.socp.soar.web.temporal.request.PlaybookExecRequest;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.domain.PlaybookStatus;
import com.socp.soar.web.persistence.store.PlaybookStore;
import com.socp.soar.web.service.LegacyPlaybookActions;
import com.socp.soar.web.service.PlaybookExecutor;
import com.socp.soar.web.service.TemporalExecutor;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Baseline-generated histories are synthetic fixtures, not production history evidence. */
class PlaybookWorkflowCompatibilityTest {

    record Scenario(String name, List<String> actions, List<Map<String, Object>> results,
                    List<Boolean> failureFlags, String status, int retries, String error) {
        @Override public String toString() { return name; }
    }

    record Call(String action, Map<String, Object> alarm, boolean failed, int index) { }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("empty", List.of(), List.of(), List.of(), "SUCCESS", 0, null),
                new Scenario("success-wire-alias", List.of("notify", "case"),
                        List.of(result("executed"), result("success")),
                        List.of(false, false), "SUCCESS", 0, null),
                new Scenario("simulation", List.of("simulate:tag"), List.of(result("simulated")),
                        List.of(false), "SIMULATED", 0, null),
                new Scenario("failure-and-skips", List.of("notify", "case", "notify again"),
                        List.of(Map.of("status", "failed", "attempts", 3, "error", "original failure"),
                                result("skipped"), result("skipped")),
                        List.of(false, true, true), "FAILED", 5, "original failure"),
                new Scenario("failed-compensation-resets-phase", List.of("notify", "case",
                        "补偿:notify rollback", "notify resumed"),
                        List.of(Map.of("status", "failed", "error", "original failure"), result("skipped"),
                                result("failed"), result("executed")),
                        List.of(false, true, true, false), "COMPENSATING", 2, "original failure"),
                new Scenario("compensation-without-main-failure", List.of("compensate:notify"),
                        List.of(result("failed")), List.of(false), "COMPENSATING", 0, null),
                new Scenario("historical-unknown-status", List.of("notify"), List.of(result("unknown")),
                        List.of(false), "SUCCESS", 1, "action failed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @Timeout(60)
    void retainsResultsAndReplaysThePreConsolidationHistory(Scenario scenario) throws Exception {
        Map<String, Object> alarm = Map.of("tenantId", "tenant-a", "playbookId", "pb-compat",
                "id", "scheduled-or-approved-stable-id", "approvalId", "approval-compat",
                "ruleId", "schedule 03:30", "severity", "HIGH");
        PlaybookExecRequest request = new PlaybookExecRequest("pb-compat", "compatibility", "schedule 03:30",
                scenario.actions(), alarm);
        Map<String, Object> baseline;
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            RecordingActivity activity = new RecordingActivity(scenario.results());
            Worker worker = env.newWorker(PlaybookWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(BaselinePlaybookWorkflow.class);
            worker.registerActivitiesImplementations(activity);
            env.start();
            PlaybookWorkflow workflow = env.getWorkflowClient().newWorkflowStub(PlaybookWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("compat-baseline-" + scenario.name())
                            .setTaskQueue(PlaybookWorkflow.TASK_QUEUE).build());
            baseline = workflow.executePlaybook(request);
            assertCalls(activity.calls, request, scenario.failureFlags());
            // Replays the former implementation's commands and recorded activity results.
            WorkflowReplayer.replayWorkflowExecution(
                    env.getWorkflowClient().fetchHistory("compat-baseline-" + scenario.name()),
                    PlaybookWorkflowImpl.class);
        }
        assertInlineCompatibility(scenario, request, baseline);
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            RecordingActivity activity = new RecordingActivity(scenario.results());
            Worker worker = env.newWorker(PlaybookWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PlaybookWorkflowImpl.class);
            worker.registerActivitiesImplementations(activity);
            env.start();
            PlaybookWorkflow workflow = env.getWorkflowClient().newWorkflowStub(PlaybookWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("compat-current-" + scenario.name())
                            .setTaskQueue(PlaybookWorkflow.TASK_QUEUE).build());
            Map<String, Object> actual = workflow.executePlaybook(request);
            assertCalls(activity.calls, request, scenario.failureFlags());
            assertThat(stableFields(actual)).isEqualTo(stableFields(baseline));
            assertThat(actual).containsEntry("status", scenario.status()).containsEntry("retryCount", scenario.retries());
            assertThat(actual.get("error")).isEqualTo(scenario.error());
            assertThat(actual.get("results")).isEqualTo(scenario.results());
            assertThat(actual.get("executionId")).asString().matches("EXEC-[0-9A-F]{8}");
            assertThat(java.time.Instant.parse(String.valueOf(actual.get("ts")))).isNotNull();
        }
    }

    private static void assertInlineCompatibility(Scenario scenario, PlaybookExecRequest request,
                                                  Map<String, Object> baseline) {
        PlaybookStore store = mock(PlaybookStore.class);
        LegacyPlaybookActions actions = mock(LegacyPlaybookActions.class);
        Playbook playbook = new Playbook(request.playbookId(), request.playbookName(), request.trigger(),
                request.actions(), true, PlaybookStatus.ACTIVE, java.time.Instant.EPOCH);
        when(store.get(playbook.id())).thenReturn(playbook);
        List<Call> calls = new ArrayList<>();
        when(actions.executeAction(anyString(), anyMap(), anyBoolean(), anyInt())).thenAnswer(invocation -> {
            int index = invocation.getArgument(3);
            calls.add(new Call(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), index));
            return scenario.results().get(index);
        });
        PlaybookExecutor executor = new PlaybookExecutor(store, mock(TemporalExecutor.class), null, actions);
        Map<String, Object> actual = TenantContext.callWith("tenant-a",
                () -> executor.runById(playbook.id(), request.alarm()));
        Map<String, Object> expected = stableFields(baseline);
        expected.remove("executor");
        assertThat(stableFields(actual)).isEqualTo(expected);
        assertCalls(calls, request, scenario.failureFlags());
    }

    private static void assertCalls(List<Call> calls, PlaybookExecRequest request, List<Boolean> flags) {
        List<Call> expected = new ArrayList<>();
        for (int index = 0; index < request.actions().size(); index++) {
            expected.add(new Call(request.actions().get(index), request.alarm(), flags.get(index), index));
        }
        assertThat(calls).containsExactlyElementsOf(expected);
    }

    private static Map<String, Object> stableFields(Map<String, Object> execution) {
        Map<String, Object> stable = new LinkedHashMap<>(execution);
        stable.remove("executionId");
        stable.remove("ts");
        return stable;
    }

    private static Map<String, Object> result(String status) {
        return Map.of("status", status);
    }

    public static final class RecordingActivity implements PlaybookActivity {
        private final List<Map<String, Object>> results;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        RecordingActivity(List<Map<String, Object>> results) {
            this.results = results;
        }

        @Override
        public Map<String, Object> executeAction(String action, Map<String, Object> alarm,
                                                 boolean activeFailed, int actionIndex) {
            calls.add(new Call(action, alarm, activeFailed, actionIndex));
            return results.get(actionIndex);
        }
    }
}
