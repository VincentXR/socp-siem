package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic fan-out execution for PARALLEL and FOREACH nodes.
 *
 * <p>The workflow remains the owner of mutable state.  This collaborator only
 * captures branch inputs, starts child workflows, and hands ordered outcomes
 * back to the owner for merging.  Keeping the state single-writer is
 * important: Temporal may complete children in any order, while the public
 * branch summary must be stable on replay.</p>
 */
final class SoarWorkflowBranchExecutor {
    private static final int MAX_PARALLELISM = 10;

    private final SoarWorkflowImpl workflow;

    SoarWorkflowBranchExecutor(SoarWorkflowImpl workflow) {
        this.workflow = workflow;
    }

    /** Deterministic summary passed from a fan-out node to its JOIN. */
    record ParallelSummary(List<Map<String, Object>> branches,
                           boolean allSucceeded,
                           boolean anySucceeded,
                           int executedNodes) { }

    /** One immutable fan-out item; all values are captured before child start. */
    private record BranchSpec(String startNode, String iterationPath, String inputJson,
                              String stopAtNode, String idSuffix) { }

    private record BranchOutcome(String status, String errorCode, String errorMessage,
                                 String iterationPath, Map<String, Object> variables,
                                 List<Map<String, Object>> nodes) { }

    /** Execute PARALLEL branches in deterministic, bounded batches. */
    ParallelSummary runParallel(String parallelNodeId, List<String> starts,
                                String joinNodeId, String parentPath,
                                int configuredParallelism) {
        Map<String, Object> base = workflow.readObject(workflow.writeJson(workflow.workflowVariables()));
        List<BranchSpec> specs = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            String branchPath = SoarWorkflowGraphSupport.branchPath(parentPath, index);
            specs.add(new BranchSpec(starts.get(index), branchPath, workflow.writeJson(base),
                    joinNodeId, parallelNodeId + "-" + index));
        }
        return runBranches(specs, maxParallelism(configuredParallelism));
    }

    /** Execute FOREACH items in bounded batches and merge results by input order. */
    ParallelSummary runForeach(String foreachNodeId, List<?> items,
                               String bodyNodeId, String doneNodeId,
                               String parentPath, String itemVariable,
                               int configuredConcurrency) {
        Map<String, Object> base = workflow.readObject(workflow.writeJson(workflow.workflowVariables()));
        List<BranchSpec> specs = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            String iterationPath = SoarWorkflowGraphSupport.branchPath(parentPath, index);
            Map<String, Object> iteration = workflow.readObject(workflow.writeJson(base));
            iteration.put("iteration", Map.of("index", index, "item", items.get(index)));
            if (itemVariable != null && !itemVariable.isBlank()) {
                iteration.put(itemVariable.replaceFirst("^vars\\.", ""), items.get(index));
            }
            specs.add(new BranchSpec(bodyNodeId, iterationPath, workflow.writeJson(iteration),
                    doneNodeId, foreachNodeId + "-" + index));
        }
        return runBranches(specs, maxParallelism(configuredConcurrency));
    }

    private ParallelSummary runBranches(List<BranchSpec> specs, int concurrency) {
        List<BranchOutcome> outcomes = new ArrayList<>();
        int width = Math.max(1, Math.min(concurrency, Math.max(1, specs.size())));
        for (int offset = 0; offset < specs.size(); offset += width) {
            int end = Math.min(specs.size(), offset + width);
            List<Promise<SoarWorkflowResult>> promises = new ArrayList<>();
            for (int index = offset; index < end; index++) {
                BranchSpec spec = specs.get(index);
                SoarWorkflow child = Workflow.newChildWorkflowStub(SoarWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId(branchWorkflowId(spec.idSuffix(), spec.iterationPath()))
                                .setTaskQueue(SoarWorkflow.TASK_QUEUE)
                                .build());
                SoarWorkflowRequest request = SoarWorkflowRequest.branchOf(
                        workflow.currentRequest(), workflow.rootNode().toString(), spec.inputJson(),
                        spec.startNode(), spec.iterationPath(), spec.stopAtNode());
                promises.add(Async.function(child::execute, request));
            }
            // Promise.get is intentionally performed in input order. Temporal
            // still runs children concurrently, but merge order is replay-safe.
            for (int index = offset; index < end; index++) {
                SoarWorkflowResult result;
                try {
                    result = promises.get(index - offset).get();
                } catch (RuntimeException failure) {
                    // A child can fail before returning a typed result (for
                    // example, a projection Activity failure). Preserve a
                    // terminal branch result so the parent never remains
                    // silently RUNNING.
                    result = new SoarWorkflowResult(workflow.requestRun(),
                            workflow.currentRequest().versionId(), "FAILED", "[]",
                            "CHILD_WORKFLOW_FAILED", workflow.safe(failure.getMessage()), "{}");
                }
                List<Map<String, Object>> childNodes = workflow.readObjects(result.nodesJson());
                Map<String, Object> childVariables = workflow.readObject(result.variablesJson());
                outcomes.add(new BranchOutcome(result.status(), result.errorCode(),
                        workflow.redactFreeText(result.errorMessage(), 2048),
                        specs.get(index).iterationPath(), childVariables, childNodes));
                workflow.appendNodeResults(childNodes);
                workflow.addStepCount(childNodes.size());
            }
        }
        // Every branch observed the same input snapshot. Merge writes only
        // after all children complete, in branch-index order.
        for (BranchOutcome outcome : outcomes) {
            workflow.mergeBranchVariables(outcome.variables());
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        boolean all = true;
        boolean any = false;
        for (BranchOutcome outcome : outcomes) {
            String status = outcome.status() == null ? "FAILED"
                    : outcome.status().toUpperCase(java.util.Locale.ROOT);
            boolean success = "SUCCEEDED".equals(status);
            boolean partial = "PARTIALLY_SUCCEEDED".equals(status);
            all &= success;
            any |= success || partial;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("iterationPath", outcome.iterationPath());
            row.put("status", status);
            row.put("nodeCount", outcome.nodes().size());
            if (outcome.errorCode() != null) row.put("errorCode", outcome.errorCode());
            if (outcome.errorMessage() != null) {
                row.put("errorMessage", workflow.redactFreeText(outcome.errorMessage(), 2048));
            }
            summary.add(row);
        }
        return new ParallelSummary(summary, all, any,
                outcomes.stream().mapToInt(value -> value.nodes().size()).sum());
    }

    int maxParallelism(int configured) {
        JsonNode root = workflow.rootNode();
        return Math.max(1, Math.min(MAX_PARALLELISM, Math.min(Math.max(1, configured),
                root == null ? MAX_PARALLELISM
                        : root.path("limits").path("maxParallelism").asInt(MAX_PARALLELISM))));
    }

    int maxParallelism() {
        JsonNode root = workflow.rootNode();
        return root == null ? MAX_PARALLELISM
                : Math.max(1, Math.min(MAX_PARALLELISM,
                root.path("limits").path("maxParallelism").asInt(MAX_PARALLELISM)));
    }

    private String branchWorkflowId(String suffix, String iterationPath) {
        return SoarWorkflowGraphSupport.branchWorkflowId(workflow.requestRun(), suffix, iterationPath);
    }
}
