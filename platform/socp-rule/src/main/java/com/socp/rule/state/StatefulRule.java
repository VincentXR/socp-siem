package com.socp.rule.state;

import com.socp.rule.rules.Rule;
import com.socp.rule.engine.DetectionResult;

/**
 * Optional rule capability for durable state checkpoints.  Stateless rules
 * continue to implement only {@link Rule}; state bytes are versioned by the
 * rule so an incompatible implementation can reject a checkpoint and fall
 * back to journal replay.
 */
public interface StatefulRule extends Rule {

    String stateVersion();

    byte[] snapshotState();

    void restoreState(byte[] serializedState);

    /** Compatibility path for rules without entity-level copy-on-write state. */
    default RuleStateMutation beginMutation() {
        byte[] before = snapshotState();
        return new RuleStateMutation() {
            private boolean committed;
            private boolean closed;

            @Override
            public DetectionResult.StateChange change(String ruleId) {
                byte[] after = snapshotState();
                return new DetectionResult.StateChange(ruleId,
                        DetectionResult.digest(before), DetectionResult.digest(after),
                        !java.util.Arrays.equals(before, after));
            }

            @Override public void rollback() { restoreState(before); }
            @Override public void commit() { committed = true; }
            @Override public void close() {
                if (closed) return;
                closed = true;
                if (!committed) rollback();
            }
        };
    }
}
