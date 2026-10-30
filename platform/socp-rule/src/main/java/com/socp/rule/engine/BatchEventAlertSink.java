package com.socp.rule.engine;

import java.util.List;

/** One atomic durable transaction for a bounded, ordered set of event results. */
public interface BatchEventAlertSink extends EventAlertSink {
    /** True only when every result and its completion receipt share one transaction. */
    boolean supportsAtomicBatch();

    /** Failure must roll back the entire batch. Views/notifications follow the commit. */
    void publishBatch(List<DetectionResult> results, List<RuleExecutionScope> scopes,
                      Runnable durableCommitGuard);
}
