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

    /**
     * A rule's declared grouping dimension does not match the dimension this
     * event routes on, so its state is only a fragment of the entity history.
     * Reported at most once per rule per rule window.
     */
    default void routingMismatched(SecurityEvent event, String ruleId,
                                   String declaredField, String eventRoutingField) {
    }

    /**
     * Wall-clock cost of evaluating one rule against one event, in nanoseconds.
     * This is the regression signal behind the ReDoS/complexity budget: a rule
     * that passes static validation but still dominates the worker thread shows
     * up here as a slow-rule observation rather than only as a wedged engine.
     */
    default void ruleEvaluated(String ruleId, long nanos) {
    }
}
