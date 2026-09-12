package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;

import java.util.List;

/**
 * Optional event-aware sink contract.
 *
 * <p>The regular {@link AlertSink} API is intentionally kept for existing
 * callers.  A durable Detection sink can use this contract to persist all
 * alerts emitted by one input event in one transaction and to acknowledge the
 * event only after the durable effects are committed.</p>
 */
public interface EventAlertSink extends AlertSink {

    /**
     * Publish the explicit calculation result. Existing sinks only need to
     * implement the event/list method; durable-aware sinks can override this
     * method to retain position, version and suppression evidence.
     */
    default void publish(DetectionResult result, Runnable durableCommitGuard) {
        if (result == null) throw new IllegalArgumentException("detection result is required");
        publish(result.event(), result.alerts(), durableCommitGuard);
    }

    /**
     * Publish the complete result of one event.  The list may be empty; an
     * empty result is still a successful, terminal processing outcome.
     */
    void publish(SecurityEvent event, List<Alert> alerts);

    /**
     * Publish through a durable boundary while holding the caller's ownership
     * fence. Implementations with a transaction should evaluate the guard
     * before committing their event result; the default keeps old sinks
     * source-compatible.
     */
    default void publish(SecurityEvent event, List<Alert> alerts, Runnable durableCommitGuard) {
        if (durableCommitGuard != null) durableCommitGuard.run();
        publish(event, alerts);
        if (durableCommitGuard != null) durableCommitGuard.run();
    }

    @Override
    default void publish(Alert alert) {
        publish(null, alert == null ? List.of() : List.of(alert));
    }
}
