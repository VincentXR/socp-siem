package com.socp.detect.web.engine;

import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Retry pauses belong to one local assignment, never to a retired worker. */
final class PartitionRetryState {
    private final Map<TopicPartition, Lease> assignments = new HashMap<>();

    synchronized Lease acquire(TopicPartition partition) {
        return assignments.computeIfAbsent(partition, Lease::new);
    }

    synchronized boolean active(Lease lease) {
        return lease == null || assignments.get(lease.partition) == lease;
    }

    synchronized boolean block(Lease lease, String category) {
        if (lease == null || !active(lease)) return false;
        if (lease.category == null) lease.blockedSince = System.currentTimeMillis();
        lease.category = category;
        return true;
    }

    synchronized String clear(Lease lease) {
        if (lease == null || !active(lease)) return null;
        String previous = lease.category;
        lease.category = null;
        lease.blockedSince = 0;
        return previous;
    }

    synchronized void revoke(TopicPartition partition) {
        assignments.remove(partition);
    }

    synchronized void clearAll() {
        assignments.clear();
    }

    synchronized Set<TopicPartition> blockedPartitions() {
        Set<TopicPartition> blocked = new HashSet<>();
        assignments.forEach((partition, lease) -> {
            if (lease.category != null) blocked.add(partition);
        });
        return blocked;
    }

    synchronized double oldestBlockedSeconds() {
        long oldest = Long.MAX_VALUE;
        for (Lease lease : assignments.values()) {
            if (lease.category != null) oldest = Math.min(oldest, lease.blockedSince);
        }
        return oldest == Long.MAX_VALUE ? 0.0
                : Math.max(0L, System.currentTimeMillis() - oldest) / 1000.0;
    }

    static final class Lease {
        private final TopicPartition partition;
        private long blockedSince;
        private String category;

        private Lease(TopicPartition partition) {
            this.partition = partition;
        }
    }
}
