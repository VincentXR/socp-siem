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
     * Publish the complete result of one event.  The list may be empty; an
     * empty result is still a successful, terminal processing outcome.
     */
    void publish(SecurityEvent event, List<Alert> alerts);

    @Override
    default void publish(Alert alert) {
        publish(null, alert == null ? List.of() : List.of(alert));
    }
}
