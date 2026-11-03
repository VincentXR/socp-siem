package com.socp.rule.state;

import java.util.IdentityHashMap;
import java.util.Map;

/** Transaction-local ownership of shared immutable evidence retained by undo. */
public final class StateUndoBudget implements AutoCloseable {
    private final Map<Object, Boolean> evidence = new IdentityHashMap<>();
    private boolean closed;

    long retain(Object value, long payloadBytes) {
        if (closed) throw new IllegalStateException("undo budget is closed");
        if (evidence.containsKey(value)) return 8L;
        boolean cached = StateSnapshotCodec.pinEvidence(value);
        evidence.put(value, cached);
        // A pinned cache entry remains charged to the unchanged process-wide
        // evidence cache. Uncached payloads consume this transaction's budget.
        return 128L + (cached ? 0L : payloadBytes);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        StateSnapshotCodec.releaseEvidence(evidence);
        evidence.clear();
    }
}
