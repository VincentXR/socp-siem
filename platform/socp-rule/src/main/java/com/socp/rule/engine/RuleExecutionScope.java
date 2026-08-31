package com.socp.rule.engine;

import com.socp.rule.model.SecurityEvent;

/** Installs caller-owned context for one event on the asynchronous rule worker. */
@FunctionalInterface
public interface RuleExecutionScope {

    RuleExecutionScope NOOP = event -> () -> { };

    Scope open(SecurityEvent event);

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
