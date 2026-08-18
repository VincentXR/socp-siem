package com.socp.detect.web.store;

import com.socp.rule.model.SecurityEvent;

import java.time.Duration;
import java.util.Set;
import java.util.List;

/**
 * Durable boundary for detection state.
 *
 * <p>The rule implementations keep their hot working set in memory for speed,
 * but the accepted event journal is the recovery source of truth.  Implementing
 * this boundary also makes the engine tests independent from JPA.</p>
 */
public interface DetectionStateStore {

    /** Claim an event id and append the event to the recovery journal. */
    boolean recordIfNew(SecurityEvent event);

    /**
     * Claim an event together with Kafka ownership metadata. Implementations
     * that persist partition/offset data override this method; the default
     * keeps direct HTTP/unit-test callers source compatible.
     */
    default boolean recordIfNew(SecurityEvent event, Integer partition, Long offset, String routingKey) {
        return recordIfNew(event);
    }

    /** Release a claim when the bounded detection queue rejected the event. */
    void remove(String eventId);

    /** Return events that should be replayed before the rule worker starts. */
    List<SecurityEvent> recent(Duration window);

    /** Return only the state owned by the currently assigned Kafka partitions. */
    default List<SecurityEvent> recentForPartitions(Set<Integer> partitions, Duration window) {
        return recent(window);
    }

    /** Human-readable configured replay window for health/operations output. */
    default String recoveryWindow() {
        return "unknown";
    }
}
