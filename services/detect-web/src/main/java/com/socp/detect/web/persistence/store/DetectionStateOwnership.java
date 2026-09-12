package com.socp.detect.web.persistence.store;

import java.time.Instant;

/**
 * Durable ownership fence for a Kafka-backed detection state unit.
 *
 * <p>Kafka assignment prevents two healthy consumers in one group from
 * receiving the same partition at the same time, but it does not stop an
 * old worker from finishing an in-flight task after a rebalance.  A fencing
 * token lets the durable boundary reject that stale worker.</p>
 */
public interface DetectionStateOwnership {

    /** Claim or renew a state unit and return its monotonically increasing fence. */
    Lease acquire(String inputTopic, int partition, int shard);

    /** Renew the lease and fail closed when another owner has fenced it. */
    void assertCurrent(Lease lease);

    /** Release a lease only when its owner and fencing token still match. */
    void release(Lease lease);

    static String unitKey(String inputTopic, int partition, int shard) {
        if (inputTopic == null || inputTopic.isBlank()) {
            throw new IllegalArgumentException("input topic is required");
        }
        if (partition < 0) throw new IllegalArgumentException("Kafka partition must be non-negative");
        if (shard < 0) throw new IllegalArgumentException("state shard must be non-negative");
        // PostgreSQL text/varchar rejects NUL bytes. The length prefix keeps
        // the printable key unambiguous even if a topic contains a delimiter.
        return inputTopic.length() + ":" + inputTopic
                + ":partition-" + partition + ":shard-" + shard;
    }

    /** Local/direct-ingest compatibility implementation without a Kafka owner. */
    static DetectionStateOwnership noop() {
        return new DetectionStateOwnership() {
            @Override
            public Lease acquire(String inputTopic, int partition, int shard) {
                return new Lease(inputTopic, partition, shard, "local", 0L, Instant.MAX);
            }

            @Override
            public void assertCurrent(Lease lease) {
                // Direct ingestion has no shared Kafka state unit to fence.
            }

            @Override
            public void release(Lease lease) {
                // Nothing to release for the local compatibility path.
            }
        };
    }

    record Lease(String inputTopic, int partition, int shard, String ownerId,
                 long fencingEpoch, Instant leaseUntil) {
        public Lease {
            if (inputTopic == null || inputTopic.isBlank()) {
                throw new IllegalArgumentException("input topic is required");
            }
            if (partition < 0) throw new IllegalArgumentException("Kafka partition must be non-negative");
            if (shard < 0) throw new IllegalArgumentException("state shard must be non-negative");
            if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("owner id is required");
            if (fencingEpoch < 0) throw new IllegalArgumentException("fencing epoch must be non-negative");
            if (leaseUntil == null) throw new IllegalArgumentException("lease expiry is required");
        }

        public String unitKey() {
            return DetectionStateOwnership.unitKey(inputTopic, partition, shard);
        }
    }

    /** Raised before stale work can cross the durable Detection boundary. */
    final class StaleStateOwnerException extends IllegalStateException {
        public StaleStateOwnerException(String message) {
            super(message);
        }
    }
}
