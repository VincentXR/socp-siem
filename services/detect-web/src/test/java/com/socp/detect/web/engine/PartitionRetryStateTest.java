package com.socp.detect.web.engine;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionRetryStateTest {
    @Test
    void retiredWorkersCannotPauseOrResumeTheNextAssignment() {
        var state = new PartitionRetryState();
        var partition = new TopicPartition("events", 0);
        var old = state.acquire(partition);
        assertTrue(state.block(old, "dependency"));
        state.revoke(partition);
        var current = state.acquire(partition);
        assertFalse(state.active(old));
        assertTrue(state.active(current));
        assertTrue(state.block(current, "ownership_lost"));

        assertFalse(state.block(old, "interrupted"));
        assertNull(state.clear(old));
        assertEquals(Set.of(partition), state.blockedPartitions());
        assertEquals("ownership_lost", state.clear(current));
        assertEquals(Set.of(), state.blockedPartitions());
    }

    @Test
    void revocationIsPartitionScopedAndSessionCleanupInvalidatesAllOldTokens() {
        var state = new PartitionRetryState();
        var first = new TopicPartition("events", 0);
        var second = new TopicPartition("events", 1);
        var one = state.acquire(first);
        var two = state.acquire(second);
        assertTrue(state.active(null), "direct non-Kafka handoffs have no assignment");
        assertFalse(state.block(null, "dependency"));
        assertNull(state.clear(null));
        state.block(one, "dependency");
        state.block(one, "interrupted");
        state.block(two, "dependency");
        assertTrue(state.oldestBlockedSeconds() >= 0);
        state.revoke(first);
        assertEquals(Set.of(second), state.blockedPartitions());
        state.clearAll();
        assertFalse(state.active(two));
        assertFalse(state.block(two, "dependency"));
        assertEquals(Set.of(), state.blockedPartitions());
        assertEquals(0.0, state.oldestBlockedSeconds());
    }
}
