package com.socp.rule.engine;

import com.socp.rule.model.SecurityEvent;

/**
 * Low-cardinality lifecycle hook for measuring one rule-engine submission.
 * Implementations must not throw or block the single stateful worker.
 */
public interface RuleProcessingObserver {

    RuleProcessingObserver NOOP = new RuleProcessingObserver() {
    };

    default void evaluationCompleted(SecurityEvent event, int emittedAlerts) {
    }

    default void durableSinksCompleted(SecurityEvent event, int emittedAlerts) {
    }

    default void processingFailed(SecurityEvent event, Throwable failure) {
    }
}
