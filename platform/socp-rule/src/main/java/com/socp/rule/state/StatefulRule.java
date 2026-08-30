package com.socp.rule.state;

import com.socp.rule.rules.Rule;

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
}
