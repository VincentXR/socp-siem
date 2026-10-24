package com.socp.rule.engine;

import com.socp.rule.model.SecurityEvent;

/** Installs caller-owned context for one event on the asynchronous rule worker. */
@FunctionalInterface
public interface RuleExecutionScope {

    RuleExecutionScope NOOP = event -> () -> { };

    Scope open(SecurityEvent event);

    /** Snapshot caller context before crossing the bounded worker queue.
     * Implementations without thread-local context keep their existing behavior. */
    default RuleExecutionScope capture() {
        return this;
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
