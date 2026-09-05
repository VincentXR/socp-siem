package com.socp.soar.web.temporal.v2;

/** Immutable payload used to start a SOAR V2 workflow. */
public record SoarV2WorkflowRequest(
        String tenantId,
        String runId,
        String versionId,
        String definitionJson,
        String inputJson,
        String executionSeriesId,
        String resumeFromNodeId,
        boolean topLevelProjection,
        String initialIterationPath,
        String stopAtNodeId
) {
    /** Backward-compatible wire constructor used by existing dispatch callers. */
    public SoarV2WorkflowRequest(String tenantId, String runId, String versionId,
                                 String definitionJson, String inputJson,
                                 String executionSeriesId, String resumeFromNodeId,
                                 boolean topLevelProjection) {
        this(tenantId, runId, versionId, definitionJson, inputJson, executionSeriesId,
                resumeFromNodeId, topLevelProjection, "", null);
    }

    public SoarV2WorkflowRequest(String tenantId, String runId, String versionId,
                                 String definitionJson, String inputJson) {
        this(tenantId, runId, versionId, definitionJson, inputJson, runId, null,
                true, "", null);
    }

    public SoarV2WorkflowRequest(String tenantId, String runId, String versionId, String definitionJson,
                                 String inputJson, String executionSeriesId, String resumeFromNodeId) {
        this(tenantId, runId, versionId, definitionJson, inputJson, executionSeriesId,
                resumeFromNodeId, true, "", null);
    }

    /** Sub-playbook children share the parent run projection and must not complete it. */
    public static SoarV2WorkflowRequest childOf(SoarV2WorkflowRequest parent, String definitionJson) {
        return childOf(parent, definitionJson, "{}");
    }

    /**
     * Build a child request with an immutable snapshot of the parent's
     * variables.  Child workflows are separate Temporal histories, so they
     * cannot read the parent's in-memory state implicitly.  Keeping this
     * payload explicit also makes replay and redaction rules auditable.
     */
    public static SoarV2WorkflowRequest childOf(SoarV2WorkflowRequest parent,
                                                String definitionJson,
                                                String inputJson) {
        return childOf(parent, definitionJson, inputJson, "");
    }

    /**
     * Child projection path prevents node-id collisions when a nested
     * playbook reuses IDs from its parent.  The path is observability-only;
     * tenant/run identity remains inherited from the parent request.
     */
    public static SoarV2WorkflowRequest childOf(SoarV2WorkflowRequest parent,
                                                String definitionJson,
                                                String inputJson,
                                                String iterationPath) {
        return branchOf(parent, definitionJson, inputJson, null,
                iterationPath == null ? "" : iterationPath, null);
    }

    /**
     * Build a deterministic child request for one parallel/foreach branch.
     * Branches use the same run projection and execution series but carry an
     * immutable copy of the parent variables and stop before the converge node.
     */
    public static SoarV2WorkflowRequest branchOf(SoarV2WorkflowRequest parent,
                                                 String definitionJson,
                                                 String inputJson,
                                                 String resumeFromNodeId,
                                                 String iterationPath,
                                                 String stopAtNodeId) {
        return new SoarV2WorkflowRequest(parent.tenantId(), parent.runId(), parent.versionId(),
                definitionJson, inputJson == null ? "{}" : inputJson,
                parent.executionSeriesId(), resumeFromNodeId, false,
                iterationPath == null ? "" : iterationPath, stopAtNodeId);
    }
}
