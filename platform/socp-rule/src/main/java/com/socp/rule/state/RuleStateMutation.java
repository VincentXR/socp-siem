package com.socp.rule.state;

import com.socp.rule.engine.DetectionResult;

/**
 * One rule evaluation's rollback boundary. The engine holds its state lock from
 * begin through close, including the durable sink. Rollback may be called before
 * close to isolate a failed rule; close without commit also rolls back.
 */
public interface RuleStateMutation extends AutoCloseable {
    DetectionResult.StateChange change(String ruleId);

    void rollback();

    void commit();

    @Override
    void close();
}
