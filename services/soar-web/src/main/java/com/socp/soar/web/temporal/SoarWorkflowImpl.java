package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarExpressionEngine;
import com.socp.soar.web.temporal.request.SoarNodeRequest;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** Deterministic interpreter for a published SOAR graph. */
public class SoarWorkflowImpl implements SoarWorkflow {
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
    private static final int MAX_SUB_PLAYBOOK_DEPTH = 5;
    private final SoarActivity activity = Workflow.newActivityStub(SoarActivity.class, ACTIVITY_OPTIONS);
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
    private SoarWorkflowRequest currentRequest;
    private SoarWorkflowBranchExecutor.ParallelSummary pendingJoin;
    private long executionDeadlineMillis;
    private final SoarWorkflowBranchExecutor branchExecutor = new SoarWorkflowBranchExecutor(this);
    private final SoarWorkflowApprovalSupport approvalSupport = new SoarWorkflowApprovalSupport(this);
    private final SoarWorkflowJsonSupport jsonSupport = new SoarWorkflowJsonSupport(mapper);

    /* Package-private workflow state accessors used by deterministic
       collaborators.  They keep Temporal state owned by this workflow while
       allowing the interpreter's large policy branches to live elsewhere. */
    JsonNode rootNode() { return root; }
    SoarWorkflowRequest currentRequest() { return currentRequest; }
    Map<String, Object> workflowVariables() { return variables; }
    void appendNodeResults(List<Map<String, Object>> results) { nodeResults.addAll(results); }
    void addStepCount(int count) { steps += count; }

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
    public SoarWorkflowResult execute(SoarWorkflowRequest request) {
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
            // An Activity can fail after its remote call but before the
            // projection transaction commits. Keep the workflow terminal and
            // always attempt the completion projection; the Activity layer and
            // recovery worker inspect durable RUNNING attempts to distinguish
            // ACTION_UNKNOWN from a projection-only failure.
            terminalStatus = "FAILED";
            errorCode = "SOAR_ACTIVITY_FAILURE";
            errorMessage = safe(failure.getMessage());
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
            activity.markRunCompleted(new SoarRunUpdate(request.tenantId(), request.runId(), terminalStatus,
                    writeJson(result), errorCode, errorMessage));
        }
        return new SoarWorkflowResult(request.runId(), request.versionId(), terminalStatus,
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
            // New dispatch payloads carry the root budget.  Reserve the slot
            // through the run projection before executing the node so every
            // branch/child workflow spends from one database-serialized pool.
            // A zero value is retained for old histories and isolated tests
            // whose payloads predate the budget field.
            if (currentRequest.executionBudgetLimit() > 0
                    && !activity.reserveNodeExecution(requestTenant(), requestRun(),
                    currentRequest.executionBudgetLimit())) {
                fail("EXECUTION_LIMIT_EXCEEDED", "run exceeded its shared node execution budget");
                break;
            }
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
                SoarNodeResult result = null;
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
                    result = activity.executeNode(new SoarNodeRequest(
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
                    // Temporal may deliver the durable UNKNOWN_RESOLUTION
                    // signal just before this Activity returns. Preserve a
                    // matching pending decision instead of clearing it when
                    // the workflow enters its await state; unrelated or
                    // stale node decisions are discarded.
                    boolean preResolved = nodeId.equals(unknownNodeId) && unknownResolution != null;
                    unknownNodeId = nodeId;
                    if (!preResolved) {
                        unknownResolution = null;
                        unknownEvidence = null;
                        unknownReason = null;
                    }
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
                        SoarNodeResult compensation = activity.compensateNode(
                                new SoarNodeRequest(requestTenant(), requestRun(), nodeId, type,
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
                SoarWorkflowBranchExecutor.ParallelSummary summary = branchExecutor.runParallel(
                        nodeId, branches, join, path,
                        node.path("limits").path("maxParallelism").asInt(branchExecutor.maxParallelism()));
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
                SoarWorkflowBranchExecutor.ParallelSummary summary = pendingJoin;
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
                SoarWorkflowBranchExecutor.ParallelSummary summary = branchExecutor.runForeach(
                        nodeId, items, body, done, path, itemVariable, concurrency);
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
                SoarWorkflowApprovalSupport.ApprovalGateContext gate = approvalSupport.approvalGateContext(nodeId, node);
                // Activity names are part of Temporal command history.  Keep
                // old executions replayable while new runs opt into the
                // context-bearing method through an explicit version marker.
                int gateContextVersion = Workflow.getVersion("soar-approval-gate-context",
                        Workflow.DEFAULT_VERSION, 1);
                if (gateContextVersion == Workflow.DEFAULT_VERSION) {
                    activity.markRunWaitingWithPolicy(requestTenant(), requestRun(), nodeId,
                            timeoutSeconds, approvalRequired(node));
                } else {
                    activity.markRunWaitingWithContext(requestTenant(), requestRun(), nodeId,
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
                if (currentRequest.playbookDepth() >= MAX_SUB_PLAYBOOK_DEPTH) {
                    fail("SUB_PLAYBOOK_DEPTH_EXCEEDED",
                            "sub-playbook nesting exceeds the maximum depth of " + MAX_SUB_PLAYBOOK_DEPTH);
                    break;
                }
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
                SoarWorkflow childWorkflow = Workflow.newChildWorkflowStub(SoarWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId(childWorkflowId(nodeId, path))
                                .setTaskQueue(SoarWorkflow.TASK_QUEUE)
                                .build());
                SoarWorkflowResult childResult = childWorkflow.execute(
                        SoarWorkflowRequest.childOf(currentRequest, child.toString(), writeJson(variables),
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

    /** Merge child writes while protecting parent identity and trigger fields. */
    void mergeBranchVariables(Map<String, Object> branchVariables) {
        if (branchVariables == null || branchVariables.isEmpty()) return;
        for (Map.Entry<String, Object> entry : branchVariables.entrySet()) {
            // These values describe the parent run and must never be replaced
            // by a child snapshot.  All graph outputs and vars.* writes remain
            // mergeable in branch-index order.
            if (Set.of("tenantId", "trigger", "run", "iteration").contains(entry.getKey())) continue;
            variables.put(entry.getKey(), entry.getValue());
        }
    }

    private String childWorkflowId(String nodeId, String iterationPath) {
        return SoarWorkflowGraphSupport.childWorkflowId(requestRun(), nodeId, iterationPath);
    }

    private String subPlaybookPath(String nodeId, String parentPath) {
        return SoarWorkflowGraphSupport.subPlaybookPath(nodeId, parentPath);
    }

    List<Map<String, Object>> readObjects(String json) { return jsonSupport.readObjects(json); }

    Map<String, Object> actionInput(JsonNode node) {
        // The full event context is useful to a connector, but secrets from
        // an alert or a previous action must never be copied into an outbound
        // request merely because they happen to share the workflow context.
        Map<String, Object> input = new LinkedHashMap<>();
        variables.forEach((key, value) -> input.put(key, SoarWorkflowGraphSupport.redactForConnector(key, value)));
        if (node.path("parameters").isObject()) {
            Map<String, Object> parameters = mapper.convertValue(node.path("parameters"), Map.class);
            parameters.replaceAll((key, value) -> SoarWorkflowGraphSupport.redactForConnector(key, resolveBinding(value)));
            input.putAll(parameters);
        }
        if (node.path("config").isObject()) {
            input.put("config", SoarWorkflowGraphSupport.redactForConnector("config",
                    resolveBinding(mapper.convertValue(node.path("config"), Map.class))));
        }
        if (node.path("connectionRef").isTextual()) input.put("connectionRef", node.path("connectionRef").asText());
        return input;
    }

    private Map<String, Object> snapshotVariables() {
        return SoarWorkflowGraphSupport.snapshotVariables(variables, mapper,
                MAX_SNAPSHOT_BYTES, MAX_SNAPSHOT_ENTRY_BYTES);
    }

    Object redactForConnector(String key, Object value) {
        return SoarWorkflowGraphSupport.redactForConnector(key, value);
    }
    /* Tenant/run identity is workflow input, never user-controlled variable
       state. A manual caller may legitimately provide fields named trigger
       or tenantId for investigation data, but those values must not redirect
       an Activity to another tenant or run. */
    String requestTenant() { return currentRequest == null ? "" : currentRequest.tenantId(); }
    String requestRun() { return currentRequest == null ? "" : currentRequest.runId(); }
    private String idempotency(String nodeId, String path) {
        return SoarWorkflowGraphSupport.idempotency(requestTenant(), requestRun(),
                currentRequest == null ? null : currentRequest.executionSeriesId(), nodeId, path);
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
        return SoarWorkflowGraphSupport.actionIdempotency(requestTenant(), requestRun(),
                currentRequest == null ? null : currentRequest.executionSeriesId(), nodeId, path, targetNode);
    }

    /** Compensation is a separate logical operation from the primary action, so
     * it derives its own key (never "{actionKey}:compensate", which could be
     * mistaken for the primary operation by a vendor header). */
    private String compensationIdempotency(String nodeId, String path, String compensationRef, JsonNode targetNode) {
        return SoarWorkflowGraphSupport.compensationIdempotency(requestTenant(), requestRun(),
                currentRequest == null ? null : currentRequest.executionSeriesId(), nodeId, path,
                compensationRef, targetNode);
    }

    private static String deterministicHash(String value) {
        return SoarWorkflowGraphSupport.deterministicHash(value);
    }
    private boolean isTerminal() {
        return Set.of("FAILED", "ACTION_UNKNOWN", "CANCELLED", "SUPPRESSED", "TIMED_OUT",
                "PARTIALLY_SUCCEEDED").contains(terminalStatus);
    }
    private void fail(String code, String message) {
        terminalStatus = "FAILED"; errorCode = code; errorMessage = redactFreeText(message, 2048);
    }
    private int maxSteps() { return Math.max(1, Math.min(500, root == null ? 500 : root.path("limits").path("maxNodeExecutions").asInt(500))); }

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
            activity.recordNode(new SoarNodeRequest(requestTenant(), requestRun(), id, type, "", path,
                    "{}", idempotency(id, path), "", Map.of()),
                    new SoarNodeResult(status, writeJson(safeOutput), code, safeMessage));
        }
    }
    JsonNode findNode(JsonNode list, String id) {
        return SoarWorkflowGraphSupport.findNode(list, id);
    }

    private List<JsonNode> outgoing(JsonNode node) {
        return SoarWorkflowGraphSupport.outgoing(root, node);
    }

    private String edgeTo(JsonNode edge) {
        return SoarWorkflowGraphSupport.edgeTo(edge);
    }

    private String edgeForPort(String source, String... ports) {
        return SoarWorkflowGraphSupport.edgeForPort(root, source, ports);
    }

    String nextNode(String source, String branch) {
        return SoarWorkflowGraphSupport.nextNode(root, source, branch);
    }

    private String commonJoin(List<String> starts) {
        return SoarWorkflowGraphSupport.commonJoin(root, starts);
    }
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
    private String edgeText(JsonNode node, String first, String second) {
        return SoarWorkflowGraphSupport.edgeText(node, first, second);
    }
    Map<String, Object> readObject(String json) { return jsonSupport.readObject(json); }
    String writeJson(Object value) { return jsonSupport.writeJson(value); }

    String safe(String value) {
        return redactFreeText(value == null ? "workflow failure" : value, 1024);
    }
    String redactFreeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
