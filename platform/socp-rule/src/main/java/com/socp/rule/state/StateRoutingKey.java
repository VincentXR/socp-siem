package com.socp.rule.state;

import java.nio.charset.StandardCharsets;

/** Stable routing contract for stateful detection rules. */
public record StateRoutingKey(String tenantId, String routingField, String routingValue) {
    public StateRoutingKey {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (routingField == null || routingField.isBlank()) throw new IllegalArgumentException("routingField is required");
        if (routingValue == null || routingValue.isBlank()) throw new IllegalArgumentException("routingValue is required");
    }

    public int shard(int shardCount) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount must be positive");
        return shardFor(tenantId, routingField, routingValue, shardCount);
    }

    /**
     * Compute the stable shard for a routing tuple without allocating a
     * temporary record.  Keeping this operation in the platform contract
     * makes the Kafka producer, detection runtime and recovery tools use the
     * exact same hash function.
     */
    public static int shardFor(String tenantId, String routingField,
                               String routingValue, int shardCount) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount must be positive");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (routingField == null || routingField.isBlank()) {
            throw new IllegalArgumentException("routingField is required");
        }
        if (routingValue == null || routingValue.isBlank()) {
            throw new IllegalArgumentException("routingValue is required");
        }
        int hash = java.util.Arrays.hashCode((tenantId + "\u0000" + routingField + "\u0000"
                + routingValue).getBytes(StandardCharsets.UTF_8));
        return Math.floorMod(hash, shardCount);
    }

    public String scopedValue() {
        return tenantId + ":" + routingField + "=" + routingValue;
    }
}
