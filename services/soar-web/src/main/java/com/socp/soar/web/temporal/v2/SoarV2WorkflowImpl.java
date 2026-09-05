package com.socp.soar.web.temporal.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarExpressionEngine;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** Deterministic interpreter for a published SOAR V2 graph. */
public class SoarV2WorkflowImpl implements SoarV2Workflow {
    /**
     * Explicit Temporal contract (design §8.2.8): Start-to-Close bounds a single
     * attempt, Schedule-to-Close bounds queueing plus attempts, and the retry
     * policy is explicit — the workflow owns action-level retry semantics, so
     * Temporal-side automatic retries are disabled (maximumAttempts = 1).
     */
    private static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setScheduleToCloseTimeout(Duration.ofMinutes(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build();
    private static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    private static final int MAX_SNAPSHOT_ENTRY_BYTES = 64 * 1024;
    private final SoarV2Activity activity = Workflow.newActivityStub(SoarV2Activity.class, ACTIVITY_OPTIONS);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean cancelled;
    private Boolean humanDecision;
    private boolean humanExpired;
    private String waitingApprovalKey;
    private String manualInputJson;
    private String waitingManualNodeId;
    private Map<String, Object> variables = new LinkedHashMap<>();
    private List<Map<String, Object>> nodeResults = new ArrayList<>();
    private int steps;
    private String terminalStatus = "SUCCEEDED";
    private String errorCode;
    private String errorMessage;
    private String unknownNodeId;
    private String unknownResolution;
    private String unknownEvidence;
    private String unknownReason;
    private JsonNode root;
    private boolean actionFailed;
    private SoarV2WorkflowRequest currentRequest;
    private ParallelSummary pendingJoin;
    private long executionDeadlineMillis;

    /** Deterministic summary passed from a PARALLEL/FOREACH fan-out to its join. */
    private record ParallelSummary(List<Map<String, Object>> branches,
                                   boolean allSucceeded,
                                   boolean anySucceeded,
                                   int executedNodes) { }

    /** Immutable context displayed to an approver and bound to the gate. */
    private record ApprovalGateContext(String actionRef, String inputHash,
                                       String targetSnapshotJson) { }

    @Override public void cancel() { cancelled = true; }
    @Override public void approve() { if (waitingApprovalKey != null) humanDecision = Boolean.TRUE; }
    @Override public void reject() { if (waitingApprovalKey != null) humanDecision = Boolean.FALSE; }
    @Override public void approveGate(String approvalKey) {
        if (gateMatches(approvalKey)) { humanExpired = false; humanDecision = Boolean.TRUE; }
    }
    @Override public void rejectGate(String approvalKey) {
        if (gateMatches(approvalKey)) { humanExpired = false; humanDecision = Boolean.FALSE; }
    }
    @Override public void expireGate(String approvalKey) {
        if (gateMatches(approvalKey)) { humanExpired = true; humanDecision = Boolean.FALSE; }
    }
    @Override public void completeManualTask(String inputJson) {
        if (waitingManualNodeId != null) manualInputJson = inputJson == null ? "{}" : inputJson;
    }
    @Override public void completeManualTaskForNode(String nodeId, String inputJson) {
        if (waitingManualNodeId != null && waitingManualNodeId.equals(nodeId)) {
            manualInputJson = inputJson == null ? "{}" : inputJson;
        }
    }
    @Override
    public void resolveUnknown(String nodeId, String resolution, String evidence, String reason) {
        String normalized = resolution == null ? "" : resolution.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CONFIRMED_SUCCEEDED", "CONFIRMED_NOT_EXECUTED").contains(normalized)) return;
        if (unknownNodeId == null || unknownNodeId.isBlank() || unknownNodeId.equals(nodeId)) {
            unknownNodeId = nodeId;
            unknownResolution = normalized;
            unknownEvidence = redactFreeText(evidence, 4096);
            unknownReason = redactFreeText(reason, 2048);
        }
    }

    @Override
    public SoarV2WorkflowResult execute(SoarV2WorkflowRequest request) {
        currentRequest = request;
        try {
            if (request.topLevelProjection()) {
                activity.markRunStarted(request.tenantId(), request.runId());
            }
            root = mapper.readTree(request.definitionJson());
            executionDeadlineMillis = Workflow.currentTimeMillis() + executionTimeoutMillis(root);
            variables = readObject(request.inputJson());
            variables.put("tenantId", request.tenantId());
            variables.putIfAbsent("trigger", new LinkedHashMap<>(variables));
            variables.put("run", Map.of("id", request.runId(), "runId", request.runId(),
                    "versionId", request.versionId(),
                    "executionSeriesId", request.executionSeriesId() == null ? request.runId() : request.executionSeriesId()));
            runPath(request.resumeFromNodeId() == null || request.resumeFromNodeId().isBlank()
                    ? root.path("entryNodeId").asText("") : request.resumeFromNodeId(),
                    request.stopAtNodeId(), request.initialIterationPath());
            // A branch intentionally stops before its converge node.  It must
            // still report a failure to the parent JOIN instead of looking
            // successful merely because no END node was reached.
            if (request.stopAtNodeId() != null && !request.stopAtNodeId().isBlank()
                    && !isTerminal() && actionFailed) {
                terminalStatus = "FAILED";
            }
            if (steps >= maxSteps() && "SUCCEEDED".equals(terminalStatus)) {
                fail("EXECUTION_LIMIT_EXCEEDED", "graph exceeded the published execution limit");
            }
        } catch (ActivityFailure failure) {
            throw failure;
        } catch (Exception failure) {
            fail("WORKFLOW_DEFINITION_ERROR", safe(failure.getMessage()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", request.runId()); result.put("versionId", request.versionId());
        result.put("status", terminalStatus); result.put("nodes", nodeResults);
        result.put("errorCode", errorCode); result.put("errorMessage", errorMessage);
        // Persist a bounded, redacted variable snapshot with the terminal
        // projection.  Retry resumes from the failed node with the same
        // deterministic context instead of silently losing values written by
        // earlier SET_VARIABLE/ACTION nodes after a process restart.
        Map<String, Object> stateSnapshot = snapshotVariables();
        result.put("variables", stateSnapshot);
        if (request.topLevelProjection()) {
            activity.markRunCompleted(new SoarV2RunUpdate(request.tenantId(), request.runId(), terminalStatus,
                    writeJson(result), errorCode, errorMessage));
        }
        return new SoarV2WorkflowResult(request.runId(), request.versionId(), terminalStatus,
                writeJson(nodeResults), errorCode, errorMessage, writeJson(stateSnapshot));
    }

    private void runPath(String current, String stopAt, String iterationPath) {
        while (current != null && !current.isBlank() && !current.equals(stopAt)
                && !isTerminal() && steps < maxSteps()) {
            if (cancelled) { terminalStatus = "CANCELLED"; errorCode = "RUN_CANCELLED"; break; }
            if (Workflow.currentTimeMillis() >= executionDeadlineMillis) {
                fail("EXECUTION_TIMEOUT", "workflow exceeded its published execution timeout");
                break;
            }
            JsonNode node = findNode(root.path("nodes"), current);
            if (node == null) { fail("NODE_NOT_FOUND", "graph node not found: " + current); break; }
            steps++;
            String nodeId = node.path("id").asText(current);
            String type = node.path("type").asText("").toUpperCase(java.util.Locale.ROOT);
            String branch = "success";
            boolean actionUnknown = false;
            Map<String, Object> output = new LinkedHashMap<>();
            String path = iterationPath == null ? "" : iterationPath;
            if ("START".equals(type)) {
                output.put("started", true);
                addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
            } else if ("ACTION".equals(type)) {
                String actionRef = node.path("actionRef").asText("");
                Map<String, Object> input = actionInput(node);
                String connectionRef = node.path("connectionRef").asText("");
                Map<String, Object> target = readObject(node.path("target").toString());
                @SuppressWarnings("unchecked")
                Map<String, Object> safeTarget = (Map<String, Object>) redactForConnector("target", target);
                SoarV2NodeResult result = null;
                JsonNode retry = node.has("retry") ? node.path("retry")
                        : (node.has("retryPolicy") ? node.path("retryPolicy") : node.path("config").path("retry"));
                int configuredAttempts = retry.has("maxAttempts") ? retry.path("maxAttempts").asInt(1)
                        : retry.path("maximumAttempts").asInt(1);
                long configuredBackoff = retry.has("backoffSeconds") ? retry.path("backoffSeconds").asLong(1)
                        : durationSeconds(retry.path("initialInterval").asText(""), 1);
                int maxAttempts = Math.max(1, Math.min(10, configuredAttempts));
                long backoffSeconds = Math.max(0, Math.min(300, configuredBackoff));
                boolean unknownResolvedSucceeded = false;
                boolean unknownResolvedNotExecuted = false;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    result = activity.executeNode(new SoarV2NodeRequest(
                            requestTenant(), requestRun(), nodeId, type, actionRef, path,
                            writeJson(input), actionIdempotency(nodeId, path, node.path("target")), connectionRef, safeTarget, attempt));
                    if (!result.retryable() || "SUCCEEDED".equalsIgnoreCase(result.status())
                            || "UNKNOWN".equalsIgnoreCase(result.status())
                            || attempt == maxAttempts) break;
                    if (backoffSeconds > 0) Workflow.sleep(Duration.ofSeconds(backoffSeconds * attempt));
                }
                output = readObject(result.outputJson());
                branch = "SUCCEEDED".equalsIgnoreCase(result.status()) ? "success" : "failure";
                if ("UNKNOWN".equalsIgnoreCase(result.status()) || "ACTION_UNKNOWN".equalsIgnoreCase(result.status())) {
                    actionUnknown = true; errorCode = result.errorCode();
                    errorMessage = redactFreeText(result.errorMessage(), 2048); branch = "unknown";
                } else if (!"SUCCEEDED".equalsIgnoreCase(result.status())) {
                    actionFailed = true; errorCode = result.errorCode();
                    errorMessage = redactFreeText(result.errorMessage(), 2048);
                }
                variables.put(nodeId, output); variables.put("nodes." + nodeId + ".output", output);
                addNodeResult(nodeId, type, path, result.status(), output, result.errorCode(),
                        redactFreeText(result.errorMessage(), 2048));
                if (actionUnknown) {
                    // A fan-out/sub-playbook child has no operator-facing
                    // signal route of its own. Waiting here would orphan the
                    // child forever because the API signal targets the
                    // top-level workflow. Return ACTION_UNKNOWN to the parent
                    // so the durable node projection can be resolved and a
                    // safe retry can resume from that node instead.
                    if (!currentRequest.topLevelProjection()) {
                        terminalStatus = "ACTION_UNKNOWN";
                        errorCode = result.errorCode() == null ? "SOAR_ACTION_RESULT_UNKNOWN" : result.errorCode();
                        errorMessage = redactFreeText(result.errorMessage(), 2048);
                        break;
                    }
                    unknownNodeId = nodeId;
                    unknownResolution = null;
                    unknownEvidence = null;
                    unknownReason = null;
                    activity.markRunUnknown(requestTenant(), requestRun(), nodeId);
                    Workflow.await(() -> cancelled || unknownResolution != null);
                    if (cancelled) {
                        terminalStatus = "CANCELLED";
                        errorCode = "RUN_CANCELLED";
                        break;
                    }
                    if ("CONFIRMED_SUCCEEDED".equals(unknownResolution)) {
                        branch = "success";
                        actionUnknown = false;
                        unknownResolvedSucceeded = true;
                        errorCode = null;
                        errorMessage = null;
                        output.put("resolution", unknownResolution);
                        output.put("evidence", unknownEvidence);
                        activity.markRunStarted(requestTenant(), requestRun());
                    } else {
                        branch = nextNode(nodeId, "unknown") == null ? "failure" : "unknown";
                        actionUnknown = false;
                        unknownResolvedNotExecuted = true;
                        // The operator has confirmed that the remote side
                        // did not execute the action.  Continuing is allowed,
                        // but a successful END must expose the run as
                        // PARTIALLY_SUCCEEDED rather than hiding the skipped
                        // response action.
                        actionFailed = true;
                        output.put("resolution", unknownResolution);
                        output.put("evidence", unknownEvidence);
                        errorCode = null;
                        errorMessage = null;
                        activity.markRunStarted(requestTenant(), requestRun());
                    }
                    unknownNodeId = null;
                    unknownResolution = null;
                }
                boolean actionSucceeded = "SUCCEEDED".equalsIgnoreCase(result.status())
                        || unknownResolvedSucceeded;
                if (!actionUnknown && !actionSucceeded && !unknownResolvedNotExecuted) {
                    String compensationRef = compensationRef(node);
                    if (!compensationRef.isBlank()) {
                        SoarV2NodeResult compensation = activity.compensateNode(
                                new SoarV2NodeRequest(requestTenant(), requestRun(), nodeId, type,
                                        actionRef, path, writeJson(input),
                                        compensationIdempotency(nodeId, path, compensationRef, node.path("target")),
                                        connectionRef, safeTarget, 1), compensationRef);
                        output.put("compensation", readObject(compensation.outputJson()));
                        if (!"SUCCEEDED".equalsIgnoreCase(compensation.status())) {
                            output.put("compensationFailed", true);
                        }
                    }
                    String onError = onError(node);
                    if ("FAIL_RUN".equals(onError) || "COMPENSATE_THEN_FAIL".equals(onError)) {
                        fail(errorCode == null ? "ACTION_FAILED" : errorCode,
                                errorMessage == null ? "action failed" : errorMessage);
                        break;
                    }
                    if ("GOTO_ERROR_PORT".equals(onError)) {
                        branch = "error";
                    } else {
                        // CONTINUE follows the normal success edge but the
                        // terminal END remains PARTIALLY_SUCCEEDED because
                        // actionFailed is durable in the workflow state.
                        branch = "success";
                    }
                }
            } else if ("SET_VARIABLE".equals(type)) {
                String name = node.path("config").path("name").asText(nodeId);
                if (!name.startsWith("vars.") && !name.equals("vars")) { fail("VARIABLE_SCOPE_INVALID", "SET_VARIABLE can only write vars.*"); break; }
                String key = name.equals("vars") ? nodeId : name.substring("vars.".length());
                Object value = resolveValue(node.path("config").get("value")); variables.put(key, value);
                output.put("name", key); output.put("value", value); addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
            } else if ("CONDITION".equals(type)) {
                boolean matched = evaluate(node.path("expression").asText("")); branch = matched ? "true" : "false"; output.put("matched", matched);
                addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
            } else if ("SWITCH".equals(type)) {
                Object value = resolveExpression(node.path("expression").asText("")); branch = switchBranch(node, value); output.put("value", value); output.put("port", branch);
                addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
            } else if ("PARALLEL".equals(type)) {
                List<JsonNode> edges = outgoing(node);
                List<String> branches = edges.stream().map(this::edgeTo).filter(v -> v != null && !v.isBlank()).toList();
                String join = commonJoin(branches);
                if (join == null) { fail("PARALLEL_JOIN_REQUIRED", "PARALLEL must converge on a JOIN"); break; }
                ParallelSummary summary = runParallel(nodeId, branches, join, path,
                        node.path("limits").path("maxParallelism").asInt(maxParallelism()));
                pendingJoin = summary;
                output.put("branches", summary.branches());
                output.put("allSucceeded", summary.allSucceeded());
                output.put("anySucceeded", summary.anySucceeded());
                variables.put("nodes." + nodeId + ".output", output);
                if (!summary.allSucceeded()) actionFailed = true;
                addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
                if (isTerminal()) break;
                current = join; continue;
            } else if ("JOIN".equals(type)) {
                String strategy = node.path("strategy").asText("ALL_SUCCESS").toUpperCase(java.util.Locale.ROOT);
                ParallelSummary summary = pendingJoin;
                boolean allSucceeded = summary == null || summary.allSucceeded();
                boolean anySucceeded = summary != null && summary.anySucceeded();
                output.put("strategy", strategy);
                if (summary != null) output.put("branches", summary.branches());
                if ("ANY_SUCCESS".equals(strategy)) {
                    branch = anySucceeded ? "success" : "failure";
                    if (!anySucceeded) actionFailed = true;
                } else if ("ALL_SUCCESS".equals(strategy) && !allSucceeded) {
                    actionFailed = true;
                    String onError = onError(node);
                    if ("CONTINUE".equals(onError)) {
                        branch = "success";
                        output.put("onError", "CONTINUE");
                    } else if ("GOTO_ERROR_PORT".equals(onError)) {
                        branch = "error";
                        output.put("onError", onError);
                    } else {
                        output.put("onError", onError);
                        // FAIL_RUN is terminal by contract.  Do not allow an
                        // arbitrary failure edge to turn a failed join into a
                        // successful END node.
                        addNodeResult(nodeId, type, path, "FAILED", output,
                                "PARALLEL_BRANCH_FAILED", "one or more branches failed");
                        fail("PARALLEL_BRANCH_FAILED", "one or more branches failed");
                        pendingJoin = null;
                        break;
                    }
                } else if ("ALL_DONE".equals(strategy) && !allSucceeded) {
                    // ALL_DONE deliberately continues after branch failures,
                    // but the terminal END must surface that partial outcome.
                    actionFailed = true;
                }
                output.put("allSucceeded", allSucceeded);
                output.put("anySucceeded", anySucceeded);
                variables.put("nodes." + nodeId + ".output", output);
                addNodeResult(nodeId, type, path, allSucceeded || "ALL_DONE".equals(strategy) || anySucceeded
                        ? "SUCCEEDED" : "FAILED", output,
                        (!allSucceeded && "ALL_SUCCESS".equals(strategy)) ? "PARALLEL_BRANCH_FAILED" : null,
                        (!allSucceeded && "ALL_SUCCESS".equals(strategy)) ? "one or more branches failed" : null);
                pendingJoin = null;
            } else if ("FOREACH".equals(type)) {
                List<?> items = asList(resolvePath(node.path("config").path("itemsPath").asText("")));
                int max = Math.max(1, Math.min(100, node.path("limits").path("maxItems").asInt(100)));
                if (items.size() > max) { fail("FOREACH_LIMIT_EXCEEDED", "FOREACH item count exceeds its bound"); break; }
                String body = edgeForPort(nodeId, "body", "each"); String done = edgeForPort(nodeId, "done", "success");
                int concurrency = node.path("limits").path("concurrency").asInt(1);
                String itemVariable = node.path("config").path("itemVariable").asText("");
                ParallelSummary summary = runForeach(nodeId, items, body, done, path, itemVariable, concurrency);
                output.put("iterations", items.size());
                output.put("branches", summary.branches());
                output.put("allSucceeded", summary.allSucceeded());
                output.put("anySucceeded", summary.anySucceeded());
                variables.put("nodes." + nodeId + ".output", output);
                boolean foreachFailed = !summary.allSucceeded();
                String foreachStatus = foreachFailed ? "FAILED" : "SUCCEEDED";
                String foreachError = foreachFailed ? "FOREACH_ITEM_FAILED" : null;
                if (foreachFailed) actionFailed = true;
                addNodeResult(nodeId, type, path, foreachStatus, output, foreachError,
                        foreachFailed ? "one or more FOREACH items failed" : null);
                if (foreachFailed && !"CONTINUE".equalsIgnoreCase(onError(node))) {
                    fail(foreachError, "one or more FOREACH items failed");
                    break;
                }
                current = done; continue;
            } else if ("DELAY".equals(type)) {
                long seconds = Math.max(0, Math.min(86400, node.path("config").path("durationSeconds").asLong(0)));
                long remaining = remainingSeconds();
                if (seconds > 0 && remaining <= 0) {
                    fail("EXECUTION_TIMEOUT", "workflow exceeded its published execution timeout");
                    break;
                }
                if (seconds > 0) Workflow.sleep(Duration.ofSeconds(Math.min(seconds, remaining)));
                if (seconds > remaining) {
                    fail("EXECUTION_TIMEOUT", "workflow exceeded its published execution timeout");
                    break;
                }
                output.put("durationSeconds", seconds); addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
            } else if ("APPROVAL".equals(type)) {
                if (!currentRequest.topLevelProjection()) {
                    // Human signals are addressed to the top-level run. A
                    // child that reaches a gate must fail closed and let the
                    // parent JOIN expose the branch as incomplete rather than
                    // holding an unobservable Temporal execution forever.
                    terminalStatus = "WAITING_APPROVAL";
                    output.put("waitingFor", "approval");
                    addNodeResult(nodeId, type, path, terminalStatus, output,
                            "CHILD_HUMAN_GATE_UNSUPPORTED", "human gates must be outside fan-out or sub-playbooks");
                    break;
                }
                long timeoutSeconds = approvalTimeoutSeconds(node);
                waitingApprovalKey = requestRun() + ":node:" + nodeId;
                humanDecision = null;
                humanExpired = false;
                ApprovalGateContext gate = approvalGateContext(nodeId, node);
                // Activity names are part of Temporal command history.  Keep
                // old executions replayable while new runs opt into the
                // context-bearing method through an explicit version marker.
                int gateContextVersion = Workflow.getVersion("soar-approval-gate-context",
                        Workflow.DEFAULT_VERSION, 1);
                if (gateContextVersion == Workflow.DEFAULT_VERSION) {
                    activity.markRunWaitingWithPolicy(requestTenant(), requestRun(), nodeId,
                            timeoutSeconds, approvalRequired(node));
                } else {
                    activity.markRunWaitingWithPolicyV2(requestTenant(), requestRun(), nodeId,
                            timeoutSeconds, approvalRequired(node), gate.actionRef(),
                            gate.inputHash(), gate.targetSnapshotJson());
                }
                long waitSeconds = Math.min(timeoutSeconds, remainingSeconds());
                boolean decided = timeoutSeconds <= 0
                        || (waitSeconds > 0 && Workflow.await(Duration.ofSeconds(waitSeconds),
                        () -> cancelled || humanDecision != null));
                if (cancelled) { terminalStatus = "CANCELLED"; errorCode = "RUN_CANCELLED"; break; }
                if (!decided && humanDecision == null) {
                    activity.markApprovalExpired(requestTenant(), requestRun(), nodeId);
                    if (remainingSeconds() <= 0 && timeoutSeconds > waitSeconds) {
                        fail("EXECUTION_TIMEOUT", "workflow exceeded its published execution timeout");
                        break;
                    }
                }
                boolean expired = humanExpired || (!decided && humanDecision == null);
                boolean approved = !expired && decided && Boolean.TRUE.equals(humanDecision);
                String rejectedPort = nextNode(nodeId, "rejected");
                if (approved) {
                    branch = "approved";
                    activity.markRunStarted(requestTenant(), requestRun());
                } else if (rejectedPort != null) {
                    // A rejection/expiry walks the explicit "rejected" edge instead of
                    // terminating the whole run (design §8.3 port semantics).
                    branch = "rejected";
                    errorCode = expired ? "APPROVAL_EXPIRED" : "APPROVAL_REJECTED";
                    activity.markRunStarted(requestTenant(), requestRun());
                } else {
                    terminalStatus = "SUPPRESSED";
                    errorCode = expired ? "APPROVAL_EXPIRED" : "APPROVAL_REJECTED";
                    branch = "rejected";
                }
                output.put("decision", approved ? "approved" : (expired ? "expired" : "rejected"));
                humanDecision = null;
                humanExpired = false;
                waitingApprovalKey = null;
                addNodeResult(nodeId, type, path, "SUCCEEDED", output, approved ? null : errorCode, null);
                if (!approved && rejectedPort == null) break;
            } else if ("MANUAL_TASK".equals(type)) {
                if (!currentRequest.topLevelProjection()) {
                    terminalStatus = "WAITING_INPUT";
                    output.put("waitingFor", "manualTask");
                    addNodeResult(nodeId, type, path, terminalStatus, output,
                            "CHILD_HUMAN_GATE_UNSUPPORTED", "human tasks must be outside fan-out or sub-playbooks");
                    break;
                }
                String form = node.has("formSchema") ? node.get("formSchema").toString() : "{\"type\":\"object\"}";
                manualInputJson = null;
                waitingManualNodeId = nodeId;
                activity.markManualTaskWaiting(requestTenant(), requestRun(), nodeId, form, node.path("assignee").asText(""), node.path("dueAt").asText(""));
                long taskTimeoutSeconds = nodeTimeoutSeconds(node);
                long taskWaitSeconds = Math.min(taskTimeoutSeconds, remainingSeconds());
                boolean completed = taskTimeoutSeconds <= 0
                        || (taskWaitSeconds > 0 && Workflow.await(Duration.ofSeconds(taskWaitSeconds),
                        () -> cancelled || manualInputJson != null));
                if (cancelled) { terminalStatus = "CANCELLED"; errorCode = "RUN_CANCELLED"; break; }
                if (!completed) {
                    if (remainingSeconds() <= 0 && taskTimeoutSeconds > taskWaitSeconds) {
                        fail("EXECUTION_TIMEOUT", "workflow exceeded its published execution timeout");
                        break;
                    }
                    activity.markManualTaskExpired(requestTenant(), requestRun(), nodeId);
                    String timeoutNext = nextNode(nodeId, "timeout");
                    output.put("expired", true);
                    addNodeResult(nodeId, type, path, "TIMED_OUT", output, "MANUAL_TASK_EXPIRED", "manual task was not completed in time");
                    if (timeoutNext == null) { fail("MANUAL_TASK_EXPIRED", "manual task was not completed in time"); break; }
                    current = timeoutNext; continue;
                }
                Map<String, Object> manual = readObject(manualInputJson); variables.put("manual." + nodeId, manual); output.put("input", manual); branch = "completed"; manualInputJson = null; waitingManualNodeId = null; addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
            } else if ("SUB_PLAYBOOK".equals(type)) {
                JsonNode child = node.get("definition");
                if (child == null || !child.isObject()) {
                    String childVersionId = node.path("playbookVersionId").asText(
                            node.path("config").path("playbookVersionId").asText(""));
                    if (childVersionId.isBlank()) {
                        fail("SUB_PLAYBOOK_DEFINITION_REQUIRED",
                                "SUB_PLAYBOOK must reference a published version or definition");
                        break;
                    }
                    try {
                        child = mapper.readTree(activity.resolvePublishedDefinition(
                                requestTenant(), childVersionId));
                    } catch (Exception failure) {
                        fail("SUB_PLAYBOOK_NOT_FOUND", safe(failure.getMessage()));
                        break;
                    }
                    if (child == null || !child.isObject()) {
                        fail("SUB_PLAYBOOK_DEFINITION_INVALID", "published child definition is not an object");
                        break;
                    }
                }
                // Real Temporal Child Workflow (design §8.2.6): the child shares the
                // parent run projection but never completes it, and the deterministic
                // child id makes replays and retries safe.
                SoarV2Workflow childWorkflow = Workflow.newChildWorkflowStub(SoarV2Workflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId(childWorkflowId(nodeId, path))
                                .setTaskQueue(SoarV2Workflow.TASK_QUEUE)
                                .build());
                SoarV2WorkflowResult childResult = childWorkflow.execute(
                        SoarV2WorkflowRequest.childOf(currentRequest, child.toString(), writeJson(variables),
                                subPlaybookPath(nodeId, path)));
                String childStatus = childResult.status() == null ? "FAILED" : childResult.status();
                // Child output is a durable typed result.  Merge its bounded
                // variable snapshot back in deterministic child completion
                // order so downstream nodes can consume enrichment values.
                mergeBranchVariables(readObject(childResult.variablesJson()));
                if ("SUCCEEDED".equalsIgnoreCase(childStatus) || "PARTIALLY_SUCCEEDED".equalsIgnoreCase(childStatus)) {
                    branch = "success";
                    output.put("childStatus", childStatus);
                    addNodeResult(nodeId, type, path, "SUCCEEDED", output, null, null);
                } else {
                    branch = "failure";
                    output.put("childStatus", childStatus);
                    addNodeResult(nodeId, type, path, childStatus, output,
                            childResult.errorCode() == null ? "SUB_PLAYBOOK_FAILED" : childResult.errorCode(),
                            redactFreeText(childResult.errorMessage(), 2048));
                    if (nextNode(nodeId, "failure") == null) {
                        fail("SUB_PLAYBOOK_FAILED", "sub-playbook ended with status " + childStatus);
                        break;
                    }
                }
            } else if ("END".equals(type)) {
                terminalStatus = normalizeOutcome(node.path("outcome").asText("SUCCEEDED"));
                if ("SUCCEEDED".equals(terminalStatus) && actionFailed) terminalStatus = "PARTIALLY_SUCCEEDED";
                addNodeResult(nodeId, type, path, terminalStatus, Map.of("outcome", terminalStatus), null, null); break;
            } else { fail("NODE_TYPE_INVALID", "unsupported node type: " + type); break; }
            String next = nextNode(nodeId, branch);
            if (next == null && !"END".equals(type) && !isTerminal()) {
                if (actionUnknown) { terminalStatus = "ACTION_UNKNOWN"; break; }
                fail("NO_OUTGOING_EDGE", "node has no matching outgoing edge: " + nodeId); break;
            }
            current = next;
        }
        if (stopAt != null && !stopAt.isBlank() && !stopAt.equals(current)
                && !isTerminal()) {
            // A fan-out branch is only successful when it reaches the
            // declared converge node.  Treat a dead-end/accidental END as a
            // failed branch so JOIN cannot hide a missing path.
            fail("BRANCH_JOIN_NOT_REACHED", "branch did not reach its declared JOIN");
        }
    }

    /** One immutable fan-out item; all values are captured before child start. */
    private record BranchSpec(String startNode, String iterationPath, String inputJson,
                              String stopAtNode, String idSuffix) { }

    private record BranchOutcome(String status, String errorCode, String errorMessage,
                                 String iterationPath, Map<String, Object> variables,
                                 List<Map<String, Object>> nodes) { }

    /**
     * Execute PARALLEL branches as Temporal child workflows in deterministic
     * batches.  The parent launches at most the configured number of children,
     * waits for a batch in branch order, then merges variable writes in that
     * same order.  This gives real overlap for slow connector Activities while
     * keeping the workflow state single-writer and replay-safe.
     */
    private ParallelSummary runParallel(String parallelNodeId, List<String> starts,
                                        String joinNodeId, String parentPath,
                                        int configuredParallelism) {
        Map<String, Object> base = readObject(writeJson(variables));
        List<BranchSpec> specs = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            String branchPath = branchPath(parentPath, index);
            specs.add(new BranchSpec(starts.get(index), branchPath, writeJson(base),
                    joinNodeId, parallelNodeId + "-" + index));
        }
        return runBranches(specs, maxParallelism(configuredParallelism));
    }

    /** Execute FOREACH items in bounded batches and merge results by input order. */
    private ParallelSummary runForeach(String foreachNodeId, List<?> items,
                                       String bodyNodeId, String doneNodeId,
                                       String parentPath, String itemVariable,
                                       int configuredConcurrency) {
        Map<String, Object> base = readObject(writeJson(variables));
        List<BranchSpec> specs = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            String iterationPath = branchPath(parentPath, index);
            Map<String, Object> iteration = readObject(writeJson(base));
            iteration.put("iteration", Map.of("index", index, "item", items.get(index)));
            if (itemVariable != null && !itemVariable.isBlank()) {
                iteration.put(itemVariable.replaceFirst("^vars\\.", ""), items.get(index));
            }
            specs.add(new BranchSpec(bodyNodeId, iterationPath, writeJson(iteration),
                    doneNodeId, foreachNodeId + "-" + index));
        }
        return runBranches(specs, maxParallelism(configuredConcurrency));
    }

    private ParallelSummary runBranches(List<BranchSpec> specs, int concurrency) {
        List<BranchOutcome> outcomes = new ArrayList<>();
        int width = Math.max(1, Math.min(concurrency, Math.max(1, specs.size())));
        for (int offset = 0; offset < specs.size(); offset += width) {
            int end = Math.min(specs.size(), offset + width);
            List<Promise<SoarV2WorkflowResult>> promises = new ArrayList<>();
            for (int index = offset; index < end; index++) {
                BranchSpec spec = specs.get(index);
                SoarV2Workflow child = Workflow.newChildWorkflowStub(SoarV2Workflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId(branchWorkflowId(spec.idSuffix(), spec.iterationPath()))
                                .setTaskQueue(SoarV2Workflow.TASK_QUEUE)
                                .build());
                SoarV2WorkflowRequest request = SoarV2WorkflowRequest.branchOf(
                        currentRequest, root.toString(), spec.inputJson(), spec.startNode(),
                        spec.iterationPath(), spec.stopAtNode());
                promises.add(Async.function(child::execute, request));
            }
            // Promise.get is intentionally performed in input order.  Temporal
            // still runs the children concurrently, but variable merge and
            // public result ordering do not depend on completion timing.
            for (int index = offset; index < end; index++) {
                SoarV2WorkflowResult result;
                try {
                    result = promises.get(index - offset).get();
                } catch (RuntimeException failure) {
                    // A child workflow can fail before it returns a typed
                    // result (for example a projection Activity failure).
                    // Convert that failure into a branch outcome so the
                    // parent still records a terminal PARTIALLY_SUCCEEDED /
                    // FAILED projection instead of becoming an unobservable
                    // RUNNING run.
                    result = new SoarV2WorkflowResult(requestRun(), currentRequest.versionId(),
                            "FAILED", "[]", "CHILD_WORKFLOW_FAILED", safe(failure.getMessage()), "{}");
                }
                List<Map<String, Object>> childNodes = readObjects(result.nodesJson());
                Map<String, Object> childVariables = readObject(result.variablesJson());
                outcomes.add(new BranchOutcome(result.status(), result.errorCode(),
                        redactFreeText(result.errorMessage(), 2048), specs.get(index).iterationPath(),
                        childVariables, childNodes));
                nodeResults.addAll(childNodes);
                steps += childNodes.size();
            }
        }
        // Merge all branch writes after every branch has observed the same
        // input snapshot.  Later branch indexes win on an explicit conflict,
        // which is deterministic and visible in the branch summary.
        for (BranchOutcome outcome : outcomes) mergeBranchVariables(outcome.variables());
        List<Map<String, Object>> summary = new ArrayList<>();
        boolean all = true;
        boolean any = false;
        for (BranchOutcome outcome : outcomes) {
            String status = outcome.status() == null ? "FAILED" : outcome.status().toUpperCase(java.util.Locale.ROOT);
            boolean success = "SUCCEEDED".equals(status);
            boolean partial = "PARTIALLY_SUCCEEDED".equals(status);
            all &= success;
            any |= success || partial;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("iterationPath", outcome.iterationPath());
            row.put("status", status);
            row.put("nodeCount", outcome.nodes().size());
            if (outcome.errorCode() != null) row.put("errorCode", outcome.errorCode());
            if (outcome.errorMessage() != null) row.put("errorMessage", redactFreeText(outcome.errorMessage(), 2048));
            summary.add(row);
        }
        return new ParallelSummary(summary, all, any,
                outcomes.stream().mapToInt(value -> value.nodes().size()).sum());
    }

    private void mergeBranchVariables(Map<String, Object> branchVariables) {
        if (branchVariables == null || branchVariables.isEmpty()) return;
        for (Map.Entry<String, Object> entry : branchVariables.entrySet()) {
            // These values describe the parent run and must never be replaced
            // by a child snapshot.  All graph outputs and vars.* writes remain
            // mergeable in branch-index order.
            if (Set.of("tenantId", "trigger", "run", "iteration").contains(entry.getKey())) continue;
            variables.put(entry.getKey(), entry.getValue());
        }
    }

    private String branchWorkflowId(String suffix, String iterationPath) {
        String path = iterationPath == null || iterationPath.isBlank()
                ? "root" : iterationPath.replace('/', '-');
        String id = "soar-v2-branch-" + requestRun() + "-" + suffix + "-" + path;
        if (id.length() <= 240) return id;
        // Temporal workflow IDs are bounded.  Truncating alone can make two
        // long iteration paths collide; keep a deterministic hash suffix so
        // retries/replays address the exact same child without aliasing a
        // sibling branch.
        String hash = Integer.toUnsignedString(id.hashCode(), 16);
        int keep = Math.max(1, 240 - hash.length() - 1);
        return id.substring(0, keep) + "-" + hash;
    }

    private String childWorkflowId(String nodeId, String iterationPath) {
        String path = iterationPath == null || iterationPath.isBlank()
                ? "root" : iterationPath.replace('/', '-');
        String id = "soar-v2-child-" + requestRun() + "-" + nodeId + "-" + path;
        if (id.length() <= 240) return id;
        String hash = Integer.toUnsignedString(id.hashCode(), 16);
        int keep = Math.max(1, 240 - hash.length() - 1);
        return id.substring(0, keep) + "-" + hash;
    }

    private String subPlaybookPath(String nodeId, String parentPath) {
        String prefix = parentPath == null || parentPath.isBlank() ? "" : parentPath + "/";
        String raw = prefix + "sub-" + nodeId;
        if (raw.length() <= 512) return raw;
        String hash = Integer.toUnsignedString(raw.hashCode(), 16);
        int keep = Math.max(1, 512 - hash.length() - 1);
        return raw.substring(0, keep) + "-" + hash;
    }

    private String branchPath(String parentPath, int index) {
        String suffix = String.valueOf(index);
        return parentPath == null || parentPath.isBlank() ? suffix : parentPath + "/" + suffix;
    }

    private int maxParallelism(int configured) {
        return Math.max(1, Math.min(MAX_PARALLELISM, Math.min(Math.max(1, configured),
                root == null ? MAX_PARALLELISM : root.path("limits").path("maxParallelism").asInt(MAX_PARALLELISM))));
    }

    private int maxParallelism() {
        return root == null ? MAX_PARALLELISM
                : Math.max(1, Math.min(MAX_PARALLELISM,
                root.path("limits").path("maxParallelism").asInt(MAX_PARALLELISM)));
    }

    private static final int MAX_PARALLELISM = 10;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readObjects(String json) {
        try {
            JsonNode value = mapper.readTree(json == null ? "[]" : json);
            if (value == null || !value.isArray()) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode item : value) if (item != null && item.isObject()) {
                result.add(mapper.convertValue(item, Map.class));
            }
            return result;
        } catch (Exception ignored) { return List.of(); }
    }

    private Map<String, Object> actionInput(JsonNode node) {
        // The full event context is useful to a connector, but secrets from
        // an alert or a previous action must never be copied into an outbound
        // request merely because they happen to share the workflow context.
        Map<String, Object> input = new LinkedHashMap<>();
        variables.forEach((key, value) -> input.put(key, redactForConnector(key, value)));
        if (node.path("parameters").isObject()) {
            Map<String, Object> parameters = mapper.convertValue(node.path("parameters"), Map.class);
            parameters.replaceAll((key, value) -> redactForConnector(key, resolveBinding(value)));
            input.putAll(parameters);
        }
        if (node.path("config").isObject()) {
            input.put("config", redactForConnector("config",
                    resolveBinding(mapper.convertValue(node.path("config"), Map.class))));
        }
        if (node.path("connectionRef").isTextual()) input.put("connectionRef", node.path("connectionRef").asText());
        return input;
    }

    private Map<String, Object> snapshotVariables() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        int used = 2;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            Object safe = redactForConnector(entry.getKey(), entry.getValue());
            String encoded = writeJson(safe);
            int bytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > MAX_SNAPSHOT_ENTRY_BYTES || used + bytes > MAX_SNAPSHOT_BYTES) {
                // Keep retry/resume deterministic without allowing a large
                // alert or action output to overflow Temporal payload limits.
                snapshot.put(entry.getKey(), Map.of("truncated", true,
                        "originalBytes", bytes));
                used += 48;
            } else {
                snapshot.put(entry.getKey(), safe);
                used += bytes;
            }
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Object redactForConnector(String key, Object value) {
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || lower.contains("authorization") || lower.equals("cookie")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : map.entrySet()) {
                String childKey = String.valueOf(item.getKey());
                out.put(childKey, redactForConnector(childKey, item.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(redactForConnector("", item));
            return out;
        }
        return value;
    }
    /* Tenant/run identity is workflow input, never user-controlled variable
       state. A manual caller may legitimately provide fields named trigger
       or tenantId for investigation data, but those values must not redirect
       an Activity to another tenant or run. */
    private String requestTenant() { return currentRequest == null ? "" : currentRequest.tenantId(); }
    private String requestRun() { return currentRequest == null ? "" : currentRequest.runId(); }
    private String idempotency(String nodeId, String path) {
        String series = currentRequest == null || currentRequest.executionSeriesId() == null
                || currentRequest.executionSeriesId().isBlank() ? requestRun()
                : currentRequest.executionSeriesId();
        String raw = series + ":" + nodeId + ":" + (path == null ? "" : path);
        if (raw.length() <= 240) return raw;
        // Vendor headers and the node projection are bounded to 255 bytes.
        // Preserve a readable prefix but add a deterministic hash so long
        // FOREACH paths cannot collide after truncation.
        String hash = deterministicHash(raw);
        int keep = Math.max(1, 240 - hash.length() - 1);
        return raw.substring(0, keep) + ":" + hash;
    }

    /**
     * Action idempotency key per design 7.3:
     * sha256(tenantId, executionSeriesId, nodeId, iterationPath, logicalTarget).
     * The opaque 64-hex form is stable across retries of the same logical
     * action and rotates whenever the tenant, series, node, iteration or the
     * resolved target changes, so a target change can never reuse a key that
     * a vendor already associated with a different destination.
     */
    private String actionIdempotency(String nodeId, String path, JsonNode targetNode) {
        return sha256Hex(idempotencyParts(nodeId, path, targetNode, null));
    }

    /** Compensation is a separate logical operation from the primary action, so
     * it derives its own key (never "{actionKey}:compensate", which could be
     * mistaken for the primary operation by a vendor header). */
    private String compensationIdempotency(String nodeId, String path, String compensationRef, JsonNode targetNode) {
        return sha256Hex(idempotencyParts(nodeId, path, targetNode,
                "compensate:" + (compensationRef == null ? "" : compensationRef)));
    }

    private String idempotencyParts(String nodeId, String path, JsonNode targetNode, String suffix) {
        String series = currentRequest == null || currentRequest.executionSeriesId() == null
                || currentRequest.executionSeriesId().isBlank() ? requestRun()
                : currentRequest.executionSeriesId();
        String target = targetNode == null || targetNode.isNull() || targetNode.isMissingNode()
                ? "" : targetNode.toString();
        StringBuilder parts = new StringBuilder();
        parts.append(requestTenant()).append('\n').append(series).append('\n')
                .append(nodeId).append('\n').append(path == null ? "" : path).append('\n').append(target);
        if (suffix != null) parts.append('\n').append(suffix);
        return parts.toString();
    }

    private static String deterministicHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int index = 0; index < 8; index++) out.append(String.format("%02x", digest[index]));
            return out.toString();
        } catch (Exception ignored) {
            // SHA-256 is required by the JDK; retain a deterministic fallback
            // for unusual restricted runtimes without widening the key.
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }
    private boolean isTerminal() { return Set.of("FAILED", "ACTION_UNKNOWN", "CANCELLED", "SUPPRESSED", "TIMED_OUT").contains(terminalStatus); }
    private void fail(String code, String message) {
        terminalStatus = "FAILED"; errorCode = code; errorMessage = redactFreeText(message, 2048);
    }
    private int maxSteps() { return Math.max(1, Math.min(500, root == null ? 500 : root.path("limits").path("maxNodeExecutions").asInt(500))); }

    /**
     * Resolve the action controlled by an APPROVAL node and capture only a
     * bounded, redacted target snapshot.  Definitions commonly put the
     * approval immediately before the dangerous ACTION, so that form is
     * supported in addition to an explicit actionRef/target on the gate.
     * The hash is over the sanitized input and is intentionally full SHA-256;
     * it lets the API prove that a later decision was made for the same
     * parameters without persisting those parameters in the approval row.
     */
    private ApprovalGateContext approvalGateContext(String nodeId, JsonNode approvalNode) {
        JsonNode controlled = approvalNode;
        String actionRef = approvalNode == null ? "" : approvalNode.path("actionRef").asText("").trim();
        if (actionRef.isBlank() && approvalNode != null) {
            String next = nextNode(nodeId, "approved");
            JsonNode candidate = findNode(root.path("nodes"), next);
            if (candidate != null && "ACTION".equalsIgnoreCase(candidate.path("type").asText(""))) {
                controlled = candidate;
                actionRef = candidate.path("actionRef").asText("").trim();
            }
        }
        Map<String, Object> input = controlled == null ? Map.of() : actionInput(controlled);
        String inputJson = writeJson(redactForConnector("input", input));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (!actionRef.isBlank()) snapshot.put("actionRef", actionRef);
        if (controlled != null && controlled.path("connectionRef").isTextual()
                && !controlled.path("connectionRef").asText("").isBlank()) {
            snapshot.put("connectionRef", controlled.path("connectionRef").asText(""));
        }
        if (controlled != null && controlled.has("target")) {
            Map<String, Object> target = readObject(controlled.path("target").toString());
            snapshot.put("target", redactForConnector("target", target));
        }
        Map<String, Object> policy = approvalPolicySnapshot(approvalNode);
        if (!policy.isEmpty()) snapshot.put("approvalPolicy", policy);
        String snapshotJson = writeJson(snapshot);
        if (snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            snapshotJson = writeJson(Map.of("truncated", true,
                    "sha256", sha256Hex(snapshotJson), "originalBytes",
                    snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        }
        return new ApprovalGateContext(actionRef, sha256Hex(inputJson), snapshotJson);
    }

    /**
     * Copy only the role/group allow-list from the immutable published gate.
     * Approval policy is data carried to the Activity projection; it is never
     * evaluated in the deterministic Workflow and therefore cannot be changed
     * by a signal or by workflow variables.
     */
    private Map<String, Object> approvalPolicySnapshot(JsonNode approvalNode) {
        if (approvalNode == null) return Map.of();
        JsonNode policy = approvalNode.path("policy").isObject()
                ? approvalNode.path("policy") : approvalNode.path("config");
        if (!policy.isObject()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        copyPolicyList(policy, result, "allowedRoles", "approverRoles");
        copyPolicyList(policy, result, "allowedGroups", "approverGroups");
        if (policy.has("approvalsRequired") && policy.path("approvalsRequired").isIntegralNumber()) {
            result.put("approvalsRequired", Math.max(1, Math.min(20, policy.path("approvalsRequired").asInt())));
        } else if (policy.has("requiredApprovals") && policy.path("requiredApprovals").isIntegralNumber()) {
            result.put("approvalsRequired", Math.max(1, Math.min(20, policy.path("requiredApprovals").asInt())));
        }
        return result;
    }

    private void copyPolicyList(JsonNode policy, Map<String, Object> target,
                                String canonical, String alias) {
        JsonNode values = policy.path(canonical).isArray() ? policy.path(canonical) : policy.path(alias);
        if (values == null || !values.isArray()) return;
        List<String> safe = new ArrayList<>();
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && !value.asText().isBlank()
                    && value.asText().length() <= 128 && safe.size() < 64) {
                safe.add(value.asText().trim());
            }
        }
        if (!safe.isEmpty()) target.put(canonical, safe);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception failure) {
            // SHA-256 is mandatory in the JDK; this deterministic fallback is
            // only for unusual restricted runtimes and is never user data.
            return Integer.toUnsignedString(String.valueOf(value).hashCode(), 16);
        }
    }

    /** Approval gate: bounded by node config, default 24h, hard-capped at 7 days. */
    private long approvalTimeoutSeconds(JsonNode node) {
        long configured = node.path("config").path("timeoutSeconds").asLong(node.path("timeoutSeconds").asLong(24 * 3600));
        return Math.max(0, Math.min(7 * 24 * 3600L, configured));
    }
    private int approvalRequired(JsonNode node) {
        JsonNode policy = node.path("policy").isObject() ? node.path("policy") : node.path("config");
        int configured = policy.path("approvalsRequired").asInt(policy.path("requiredApprovals").asInt(1));
        return Math.max(1, Math.min(20, configured));
    }
    private boolean gateMatches(String approvalKey) {
        return approvalKey != null && !approvalKey.isBlank()
                && waitingApprovalKey != null && waitingApprovalKey.equals(approvalKey);
    }
    private long executionTimeoutMillis(JsonNode definition) {
        String configured = definition == null ? "" : definition.path("limits").path("executionTimeout").asText("");
        try {
            long millis = configured.isBlank() ? Duration.ofHours(24).toMillis()
                    : Duration.parse(configured).toMillis();
            return Math.max(1000L, Math.min(Duration.ofDays(30).toMillis(), millis));
        } catch (Exception ignored) {
            return Duration.ofHours(24).toMillis();
        }
    }
    private long remainingSeconds() {
        long millis = executionDeadlineMillis - Workflow.currentTimeMillis();
        return Math.max(0, (millis + 999) / 1000);
    }
    /** Manual task: bounded by node config, default 24h (matches projection dueAt), hard-capped at 30 days. */
    private long nodeTimeoutSeconds(JsonNode node) {
        long configured = node.path("config").path("timeoutSeconds").asLong(node.path("timeoutSeconds").asLong(24 * 3600));
        return Math.max(0, Math.min(30 * 24 * 3600L, configured));
    }
    private void addNodeResult(String id, String type, String path, String status,
                                Map<String, Object> output, String code, String message) {
        Map<String, Object> safeOutput = readObject(writeJson(redactForConnector("", output)));
        String safeMessage = redactFreeText(message, 2048);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", id); row.put("nodeType", type); row.put("iterationPath", path);
        row.put("status", status); row.put("output", safeOutput);
        if (code != null) row.put("errorCode", code);
        if (safeMessage != null && !safeMessage.isBlank()) row.put("errorMessage", safeMessage);
        nodeResults.add(row);
        if (!"ACTION".equals(type)) {
            activity.recordNode(new SoarV2NodeRequest(requestTenant(), requestRun(), id, type, "", path,
                    "{}", idempotency(id, path), "", Map.of()),
                    new SoarV2NodeResult(status, writeJson(safeOutput), code, safeMessage));
        }
    }
    private JsonNode findNode(JsonNode list, String id) { if (!list.isArray()) return null; for (JsonNode node : list) if (id.equals(node.path("id").asText())) return node; return null; }
    private List<JsonNode> outgoing(JsonNode node) { List<JsonNode> out = new ArrayList<>(); if (node == null) return out; for (JsonNode edge : root.path("edges")) if (node.path("id").asText().equals(edgeText(edge, "from", "source"))) out.add(edge); return out; }
    private String edgeTo(JsonNode edge) { return edgeText(edge, "to", "target"); }
    private String edgeForPort(String source, String... ports) { for (String port : ports) { String found = nextNode(source, port); if (found != null) return found; } return nextNode(source, "success"); }
    private String nextNode(String source, String branch) { String fallback = null; for (JsonNode edge : root.path("edges")) { if (!source.equals(edgeText(edge, "from", "source"))) continue; String port = edgeText(edge, "port", "when"); String to = edgeTo(edge); if (fallback == null && (port.isBlank() || "default".equalsIgnoreCase(port))) fallback = to; if (branch.equalsIgnoreCase(port)) return to; } return fallback; }
    private String commonJoin(List<String> starts) { if (starts.isEmpty()) return null; Set<String> candidates = null; for (String start : starts) { Set<String> reachable = new HashSet<>(); ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(start); while (!queue.isEmpty()) { String id = queue.removeFirst(); if (!reachable.add(id)) continue; JsonNode node = findNode(root.path("nodes"), id); if (node != null && "JOIN".equalsIgnoreCase(node.path("type").asText())) break; if (node != null) for (JsonNode edge : outgoing(node)) if (edgeTo(edge) != null) queue.add(edgeTo(edge)); } if (candidates == null) candidates = reachable; else candidates.retainAll(reachable); } return candidates == null ? null : candidates.stream().filter(id -> { JsonNode node = findNode(root.path("nodes"), id); return node != null && "JOIN".equalsIgnoreCase(node.path("type").asText()); }).sorted().findFirst().orElse(null); }
    private Object resolveExpression(String expression) { String text = expression == null ? "" : expression.trim(); if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) return text.substring(1, text.length() - 1); Object value = resolvePath(text); return value == null ? text : value; }
    private boolean evaluate(String expression) { return SoarExpressionEngine.evaluate(expression, variables); }
    private Object resolvePath(String path) {
        String value = path == null ? "" : path.trim().replaceFirst("^(vars|variables)\\.", "");
        if (variables.containsKey(value)) return variables.get(value);
        String[] parts = value.split("\\.");
        for (int prefixLength = parts.length - 1; prefixLength > 0; prefixLength--) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, prefixLength));
            if (!variables.containsKey(prefix)) continue;
            Object current = variables.get(prefix);
            for (int index = prefixLength; index < parts.length; index++) {
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(parts[index]);
            }
            return current;
        }
        Object current = variables;
        for (String part : parts) { if (current instanceof Map<?, ?> map) current = map.get(part); else return null; }
        return current;
    }
    private Object resolveValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual() && value.asText().startsWith("$expr:")) {
            return resolveExpression(value.asText().substring(6));
        }
        if (value.isObject() && value.size() == 1 && value.has("$expr")) {
            return resolveExpression(value.path("$expr").asText(""));
        }
        return mapper.convertValue(value, Object.class);
    }
    private String switchBranch(JsonNode node, Object value) { JsonNode cases = node.get("cases"); if (cases == null || cases.isMissingNode()) cases = node.path("config").get("cases"); if (cases != null && cases.isArray()) for (JsonNode item : cases) { JsonNode expected = item.get("value"); if (expected == null) expected = item.get("when"); String port = edgeText(item, "port", "toPort"); if (expected != null && String.valueOf(value).equalsIgnoreCase(expected.asText()) && !port.isBlank()) return port; } return node.path("config").path("defaultPort").asText("default"); }
    private List<?> asList(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private String normalizeOutcome(String outcome) { String value = outcome == null ? "SUCCEEDED" : outcome.toUpperCase(); return Set.of("SUCCEEDED", "FAILED", "SUPPRESSED", "TIMED_OUT", "CANCELLED", "PARTIALLY_SUCCEEDED").contains(value) ? value : "SUCCEEDED"; }
    private long durationSeconds(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            if (value.matches("PT[0-9]+S")) return Long.parseLong(value.substring(2, value.length() - 1));
            return Math.max(0, Duration.parse(value).toSeconds());
        } catch (Exception ignored) { return fallback; }
    }
    private String onError(JsonNode node) {
        String value = node == null ? "" : node.path("onError").asText("");
        if (value.isBlank() && node != null) value = node.path("config").path("onError").asText("");
        return value.isBlank() ? "FAIL_RUN" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
    private String compensationRef(JsonNode node) {
        if (node == null) return "";
        String value = node.path("compensateRef").asText("");
        if (value.isBlank()) value = node.path("config").path("compensateRef").asText("");
        if (value.isBlank()) value = node.path("compensation").path("actionRef").asText("");
        return value == null ? "" : value.trim();
    }
    @SuppressWarnings("unchecked")
    private Object resolveBinding(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("$expr")) return resolveExpression(String.valueOf(map.get("$expr")));
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), resolveBinding(entry.getValue()));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::resolveBinding).toList();
        if (value instanceof String text) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]{0,255})}").matcher(text);
            if (matcher.matches()) return resolvePath(matcher.group(1));
            StringBuffer out = new StringBuffer();
            while (matcher.find()) matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(String.valueOf(resolvePath(matcher.group(1)))));
            matcher.appendTail(out);
            return out.toString();
        }
        return value;
    }
    private String edgeText(JsonNode node, String first, String second) { String value = node.path(first).asText(""); return value.isBlank() ? node.path(second).asText("") : value; }
    @SuppressWarnings("unchecked") private Map<String, Object> readObject(String json) { try { JsonNode node = mapper.readTree(json == null ? "{}" : json); return node != null && node.isObject() ? mapper.convertValue(node, Map.class) : new LinkedHashMap<>(); } catch (Exception ignored) { return new LinkedHashMap<>(); } }
    private String writeJson(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ignored) { return "{}"; } }
    private String safe(String value) {
        return redactFreeText(value == null ? "workflow failure" : value, 1024);
    }
    private String redactFreeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
