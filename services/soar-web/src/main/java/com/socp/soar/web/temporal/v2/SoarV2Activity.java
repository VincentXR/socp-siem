package com.socp.soar.web.temporal.v2;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface SoarV2Activity {

    @ActivityMethod
    void markRunStarted(String tenantId, String runId);

    @ActivityMethod
    void markRunWaiting(String tenantId, String runId, String nodeId);

    /** Create/update a node-level approval projection with the published
     * timeout and vote policy.  The three-argument method is retained for
     * old workers and focused tests. */
    @ActivityMethod
    void markRunWaitingWithPolicy(String tenantId, String runId, String nodeId,
                                  long timeoutSeconds, int requiredApprovals);

    /**
     * Gate projection carrying the immutable approval context.  This is a
     * separately named Activity (rather than an overload) so Temporal can
     * deploy it additively while old histories/workers continue to understand
     * the five-argument compatibility method above.
     */
    @ActivityMethod
    void markRunWaitingWithPolicyV2(String tenantId, String runId, String nodeId,
                                    long timeoutSeconds, int requiredApprovals,
                                    String actionRef, String inputHash,
                                    String targetSnapshotJson);

    @ActivityMethod
    void markRunUnknown(String tenantId, String runId, String nodeId);

    /** Persist a Temporal timer expiry for the concrete human gate. */
    @ActivityMethod
    void markApprovalExpired(String tenantId, String runId, String nodeId);

    @ActivityMethod
    void markManualTaskWaiting(String tenantId, String runId, String nodeId,
                                String formSchemaJson, String assignee, String dueAt);

    @ActivityMethod
    void markManualTaskExpired(String tenantId, String runId, String nodeId);

    @ActivityMethod
    SoarV2NodeResult executeNode(SoarV2NodeRequest request);

    /** Execute a connector-declared compensation without changing the node's
     * primary outcome.  A connector that cannot compensate returns an explicit
     * COMPENSATION_UNAVAILABLE result. */
    @ActivityMethod
    SoarV2NodeResult compensateNode(SoarV2NodeRequest request, String compensationRef);

    @ActivityMethod
    void recordNode(SoarV2NodeRequest request, SoarV2NodeResult result);

    @ActivityMethod
    void markRunCompleted(SoarV2RunUpdate update);

    /** Resolve a tenant-scoped immutable published child definition. */
    @ActivityMethod
    String resolvePublishedDefinition(String tenantId, String versionId);
}
