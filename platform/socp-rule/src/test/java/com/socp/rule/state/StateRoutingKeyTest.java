package com.socp.rule.state;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateRoutingKeyTest {
    @Test
    void routesSameInputToSameShardAndSnapshotDefensivelyCopiesBytes() {
        var first = new StateRoutingKey("tenant-a", "src_ip", "203.0.113.1");
        assertEquals(first.shard(8), new StateRoutingKey("tenant-a", "src_ip", "203.0.113.1").shard(8));
        byte[] bytes = {1, 2, 3};
        var snapshot = new DetectionStateSnapshot("R-1", "1", "tenant-a", 0, 10, bytes, Instant.EPOCH);
        bytes[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, snapshot.serializedState());
    }

    @Test
    void rejectsInvalidShardAndRoutingArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                StateRoutingKey.shardFor("tenant-a", "user", "alice", 0));
        assertThrows(IllegalArgumentException.class, () ->
                StateRoutingKey.shardFor("", "user", "alice", 2));
        assertThrows(IllegalArgumentException.class, () ->
                StateRoutingKey.shardFor("tenant-a", "user", "", 2));
    }
}
