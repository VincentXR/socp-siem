package com.socp.soar.web.temporal.v2;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.SignalMethod;

/** Durable graph runner for the versioned SOAR control plane. */
@WorkflowInterface
public interface SoarV2Workflow {

    String TASK_QUEUE = "SOCP_SOAR_V2_TASK_QUEUE";

    @WorkflowMethod
    SoarV2WorkflowResult execute(SoarV2WorkflowRequest request);

    /** Cancellation is a signal so an operator never has to mutate workflow state directly. */
    @SignalMethod
    void cancel();

    @SignalMethod
    void approve();

    @SignalMethod
    void reject();

    /** Gate-scoped signals prevent a delayed decision for one node from
     * satisfying a later APPROVAL node in the same run. */
    @SignalMethod
    void approveGate(String approvalKey);

    @SignalMethod
    void rejectGate(String approvalKey);

    @SignalMethod
    void expireGate(String approvalKey);

    @SignalMethod
    void completeManualTask(String inputJson);

    /** Node-scoped form prevents a delayed completion from satisfying a later task. */
    @SignalMethod
    void completeManualTaskForNode(String nodeId, String inputJson);

    /** Resolve a connector result that could not be proven after the request. */
    @SignalMethod
    void resolveUnknown(String nodeId, String resolution, String evidence, String reason);
}
