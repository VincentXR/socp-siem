package com.socp.rule.state;

import com.socp.rule.rules.Rule;

/**
 * Optional rule capability for durable state checkpoints.  Stateless rules
 * continue to implement only {@link Rule}; state bytes are versioned by the
 * rule so an incompatible implementation can reject a checkpoint and fall
 * back to journal replay.
 */
public interface StatefulRule extends Rule {

    /** Legacy implementations retain full-state rollback and audit digests. */
    default StateMutation beginMutation() {
        byte[] before = snapshotState();
        return new StateMutation() {
            @Override public com.socp.rule.engine.DetectionResult.StateChange change(String ruleId) {
                byte[] after = snapshotState();
                return new com.socp.rule.engine.DetectionResult.StateChange(ruleId,
                        com.socp.rule.engine.DetectionResult.digest(before),
                        com.socp.rule.engine.DetectionResult.digest(after),
                        !java.util.Arrays.equals(before, after));
            }
            @Override public long retainedBytes() { return before.length; }
            @Override public void rollback() { restoreState(before); }
            @Override public void close() { }
        };
    }

    String stateVersion();

    byte[] snapshotState();

    void restoreState(byte[] serializedState);
}
