package com.socp.soar.web.temporal.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.temporal.request.SoarV2NodeRequest;
import com.socp.soar.web.temporal.request.SoarV2WorkflowRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Shared harness for evidence tests of {@link SoarV2WorkflowImpl} running on a
 * Temporal {@link TestWorkflowEnvironment}.  This class deliberately does not
 * match the Surefire test-name patterns and is never executed as a test.
 *
 * <p>The fake Activity is hand-written because Temporal rejects Mockito
 * generated activity classes; the workflow (and any PARALLEL/FOREACH child
 * workflows it starts on the same task queue) invoke the same fake instance.
 */
public final class SoarV2WorkflowSupport {

    private SoarV2WorkflowSupport() { }

    public static final String TENANT_ID = "tenant-a";
    public static final String VERSION_ID = "test-version";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One recorded activity invocation: method name plus the deserialized arguments. */
    public record Call(String method, List<Object> args) {
        public int argCount() { return args.size(); }
        public Object arg(int index) { return args.get(index); }
    }

    // ------------------------------------------------------------------ JSON helpers

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot serialize test value", failure);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(String json) {
        try {
            Object value = MAPPER.readValue(json == null ? "{}" : json, Object.class);
            return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot parse JSON: " + json, failure);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(String json) {
        try {
            Object value = MAPPER.readValue(json == null ? "[]" : json, Object.class);
            if (!(value instanceof List<?> raw)) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : raw) if (item instanceof Map<?, ?>) result.add((Map<String, Object>) item);
            return result;
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot parse JSON array: " + json, failure);
        }
    }

    /** Ordered {@code LinkedHashMap} factory for config/limits/target payloads. */
    public static Map<String, Object> obj(Object... keysAndValues) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put(String.valueOf(keysAndValues[index]), keysAndValues[index + 1]);
        }
        return result;
    }

    // ---------------------------------------------------------- result projections

    public static List<Map<String, Object>> nodeRows(SoarV2WorkflowResult result) {
        return list(result.nodesJson());
    }

    public static Map<String, Object> variables(SoarV2WorkflowResult result) {
        return map(result.variablesJson());
    }

    public static Map<String, Object> rowById(List<Map<String, Object>> rows, String nodeId) {
        for (Map<String, Object> row : rows) {
            if (nodeId.equals(row.get("nodeId"))) return row;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> output(Map<String, Object> row) {
        Object value = row == null ? null : row.get("output");
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    // ------------------------------------------------------------- definition builder

    /**
     * Ordered, {@code LinkedHashMap}-backed builder for the playbook JSON the
     * workflow interprets.  Follows the runtime vocabulary verified in
     * {@code SoarV2WorkflowImpl}: CONDITION reads its expression from the
     * top-level node {@code expression}; FOREACH reads {@code config.itemsPath}
     * / {@code config.itemVariable} and {@code limits.concurrency}; JOIN reads
     * the node field {@code strategy}; APPROVAL/MANUAL_TASK read
     * {@code config.timeoutSeconds}.
     */
    public static final class Def {
        private final LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        private final LinkedHashMap<String, Object> nodes = new LinkedHashMap<>();
        private final List<Object> edges = new ArrayList<>();

        public Def() {
            root.put("schemaVersion", "soar.playbook/v2");
            root.put("entryNodeId", "start");
            root.put("limits", obj("maxNodeExecutions", 500, "maxParallelism", 10));
        }

        public Def entry(String entryNodeId) {
            root.put("entryNodeId", entryNodeId);
            return this;
        }

        public Def node(String id, String type, Object... keysAndValues) {
            LinkedHashMap<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("type", type);
            for (int index = 0; index < keysAndValues.length; index += 2) {
                node.put(String.valueOf(keysAndValues[index]), keysAndValues[index + 1]);
            }
            nodes.put(id, node);
            return this;
        }

        public Def edge(String from, String to) {
            return edge(from, to, null);
        }

        public Def edge(String from, String to, String port) {
            LinkedHashMap<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", from);
            edge.put("to", to);
            if (port != null && !port.isBlank()) edge.put("port", port);
            edges.add(edge);
            return this;
        }

        public String json() {
            LinkedHashMap<String, Object> rootJson = new LinkedHashMap<>(root);
            rootJson.put("nodes", new ArrayList<>(nodes.values()));
            rootJson.put("edges", new ArrayList<>(edges));
            return SoarV2WorkflowSupport.json(rootJson);
        }
    }

    // ------------------------------------------------------------------ activity fake

    /** Thread-safe recording stand-in for the SOAR V2 projection activities. */
    public static final class ActivityFake implements SoarV2Activity {

        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final Map<String, SoarV2NodeResult> nodeOverrides = new ConcurrentHashMap<>();
        private final Map<String, String> definitionOverrides = new ConcurrentHashMap<>();
        private final List<SoarV2RunUpdate> completions = new CopyOnWriteArrayList<>();

        public ActivityFake overrideNode(String nodeId, SoarV2NodeResult result) {
            nodeOverrides.put(nodeId, result);
            return this;
        }

        public ActivityFake overridePublished(String versionId, String definitionJson) {
            definitionOverrides.put(versionId, definitionJson);
            return this;
        }

        public List<Call> calls() { return calls; }

        public List<Call> callsNamed(String method) {
            return calls.stream().filter(call -> method.equals(call.method())).toList();
        }

        public long countOf(String method) { return callsNamed(method).size(); }

        public List<SoarV2NodeRequest> executeNodeRequests() {
            return callsNamed("executeNode").stream()
                    .map(call -> (SoarV2NodeRequest) call.arg(0))
                    .toList();
        }

        public List<SoarV2RunUpdate> completions() { return completions; }

        public SoarV2RunUpdate lastCompletedUpdate() {
            return completions.isEmpty() ? null : completions.get(completions.size() - 1);
        }

        public String describe() {
            Map<String, Long> counts = new TreeMap<>();
            for (Call call : calls) counts.merge(call.method(), 1L, Long::sum);
            return counts + " -> " + calls.size() + " recorded calls";
        }

        private void record(String method, Object... args) {
            calls.add(new Call(method, List.of(args)));
        }

        private SoarV2NodeResult success(String nodeId) {
            return new SoarV2NodeResult("SUCCEEDED", json(obj("echo", nodeId)), null, null);
        }

        @Override public void markRunStarted(String tenantId, String runId) {
            record("markRunStarted", tenantId, runId);
        }

        @Override public boolean reserveNodeExecution(String tenantId, String runId, int budgetLimit) {
            record("reserveNodeExecution", tenantId, runId, budgetLimit);
            return true;
        }

        @Override public void markRunWaiting(String tenantId, String runId, String nodeId) {
            record("markRunWaiting", tenantId, runId, nodeId);
        }

        @Override public void markRunWaitingWithPolicy(String tenantId, String runId, String nodeId,
                                                       long timeoutSeconds, int requiredApprovals) {
            record("markRunWaitingWithPolicy", tenantId, runId, nodeId, timeoutSeconds, requiredApprovals);
        }

        @Override public void markRunWaitingWithPolicyV2(String tenantId, String runId, String nodeId,
                                                         long timeoutSeconds, int requiredApprovals,
                                                         String actionRef, String inputHash,
                                                         String targetSnapshotJson) {
            record("markRunWaitingWithPolicyV2", tenantId, runId, nodeId, timeoutSeconds,
                    requiredApprovals, actionRef, inputHash, targetSnapshotJson);
        }

        @Override public void markRunUnknown(String tenantId, String runId, String nodeId) {
            record("markRunUnknown", tenantId, runId, nodeId);
        }

        @Override public void markApprovalExpired(String tenantId, String runId, String nodeId) {
            record("markApprovalExpired", tenantId, runId, nodeId);
        }

        @Override public void markManualTaskWaiting(String tenantId, String runId, String nodeId,
                                                    String formSchemaJson, String assignee, String dueAt) {
            record("markManualTaskWaiting", tenantId, runId, nodeId, formSchemaJson, assignee, dueAt);
        }

        @Override public void markManualTaskExpired(String tenantId, String runId, String nodeId) {
            record("markManualTaskExpired", tenantId, runId, nodeId);
        }

        @Override public SoarV2NodeResult executeNode(SoarV2NodeRequest request) {
            record("executeNode", request);
            SoarV2NodeResult override = nodeOverrides.get(request.nodeId());
            return override == null ? success(request.nodeId()) : override;
        }

        @Override public SoarV2NodeResult compensateNode(SoarV2NodeRequest request, String compensationRef) {
            record("compensateNode", request, compensationRef);
            return new SoarV2NodeResult("SUCCEEDED", json(obj("compensated", true)), null, null);
        }

        @Override public void recordNode(SoarV2NodeRequest request, SoarV2NodeResult result) {
            record("recordNode", request, result);
        }

        @Override public void markRunCompleted(SoarV2RunUpdate update) {
            record("markRunCompleted", update);
            completions.add(update);
        }

        @Override public String resolvePublishedDefinition(String tenantId, String versionId) {
            record("resolvePublishedDefinition", tenantId, versionId);
            String override = definitionOverrides.get(versionId);
            return override == null ? "{}" : override;
        }
    }

    // ------------------------------------------------------------------------ harness

    /**
     * Boots an in-memory Temporal environment, registers the real workflow
     * implementation plus {@link ActivityFake}, and starts one top-level run.
     */
    public static final class Harness implements AutoCloseable {

        private final TestWorkflowEnvironment env;
        private final ActivityFake fake = new ActivityFake();
        private final WorkflowClient client;
        private String runId;
        private WorkflowStub stub;
        private boolean started;

        public static Harness boot() {
            return new Harness();
        }

        private Harness() {
            env = TestWorkflowEnvironment.newInstance();
            Worker worker = env.newWorker(SoarV2Workflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(SoarV2WorkflowImpl.class);
            worker.registerActivitiesImplementations(fake);
            env.start();
            client = env.getWorkflowClient();
        }

        public ActivityFake fake() { return fake; }

        public TestWorkflowEnvironment env() { return env; }

        public String runId() {
            if (runId == null) throw new IllegalStateException("workflow not started yet");
            return runId;
        }

        public void startWorkflow(String definitionJson, String inputJson) {
            startWorkflow(definitionJson, inputJson, VERSION_ID);
        }

        public void startWorkflow(String definitionJson, String inputJson, String versionId) {
            if (started) throw new IllegalStateException("workflow already started");
            started = true;
            runId = UUID.randomUUID().toString();
            String workflowId = "soar-v2-test-" + runId;
            SoarV2WorkflowRequest request = new SoarV2WorkflowRequest(
                    TENANT_ID, runId, versionId, definitionJson, inputJson == null ? "{}" : inputJson);
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setTaskQueue(SoarV2Workflow.TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build();
            stub = client.newUntypedWorkflowStub(SoarV2Workflow.class.getSimpleName(), options);
            stub.start(request);
        }

        /** Gate signal keys match the runtime: {@code runId + ":node:" + nodeId}. */
        public String approvalKey(String nodeId) {
            return runId + ":node:" + nodeId;
        }

        public void approveGate(String nodeId) { signal("approveGate", approvalKey(nodeId)); }

        public void rejectGate(String nodeId) { signal("rejectGate", approvalKey(nodeId)); }

        public void expireGate(String nodeId) { signal("expireGate", approvalKey(nodeId)); }

        public void completeManualTask(String nodeId, String inputJson) {
            signal("completeManualTaskForNode", nodeId, inputJson);
        }

        public void resolveUnknown(String nodeId, String resolution, String evidence, String reason) {
            signal("resolveUnknown", nodeId, resolution, evidence, reason);
        }

        private void signal(String name, Object... args) {
            if (stub == null) throw new IllegalStateException("workflow not started yet");
            stub.signal(name, args);
        }

        /** Wait until an activity call satisfies the predicate (bounded real-time poll). */
        public void waitForCall(Predicate<Call> predicate) {
            waitForCall(predicate, Duration.ofSeconds(5));
        }

        public void waitForCall(Predicate<Call> predicate, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (Call call : fake.calls()) {
                    if (predicate.test(call)) return;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for an activity call", interrupted);
                }
            }
            throw new AssertionError("no activity call matched within " + timeout
                    + "\nrecorded activity calls: " + fake.describe());
        }

        public void waitForMethod(String method) {
            waitForCall(call -> method.equals(call.method()));
        }

        /** Bounded result read so a missed signal fails fast instead of hanging. */
        public SoarV2WorkflowResult result() {
            if (stub == null) throw new IllegalStateException("workflow not started yet");
            try {
                return stub.getResult(30, TimeUnit.SECONDS, SoarV2WorkflowResult.class);
            } catch (TimeoutException timeout) {
                throw new AssertionError("workflow did not complete within 30s; recorded activity calls: "
                        + fake.describe(), timeout);
            } catch (WorkflowException failure) {
                throw new AssertionError("workflow completed with an unhandled failure; recorded activity calls: "
                        + fake.describe(), failure);
            }
        }

        @Override public void close() {
            if (env != null) env.close();
        }
    }
}
