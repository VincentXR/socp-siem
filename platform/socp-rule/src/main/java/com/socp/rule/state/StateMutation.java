package com.socp.rule.state;

import com.socp.rule.engine.DetectionResult;

/** Event-local undo boundary. Full checkpoint serialization is a separate operation. */
public interface StateMutation extends AutoCloseable {
    DetectionResult.StateChange change(String ruleId);
    void rollback();
    /** Release the active evaluation scope but retain undo until its batch commits. */
    default void detach() { }
    default long retainedBytes() { return 0; }
    @Override void close();
}
