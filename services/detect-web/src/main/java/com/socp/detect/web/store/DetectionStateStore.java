package com.socp.detect.web.store;

import com.socp.rule.model.SecurityEvent;

import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.function.Consumer;

/**
 * Durable boundary for detection state.
 *
 * <p>The rule implementations keep their hot working set in memory for speed,
 * but the accepted event journal is the recovery source of truth.  Implementing
 * this boundary also makes the engine tests independent from JPA.</p>
 */
public interface DetectionStateStore {

    /** Claim an event, retaining the distinction between replayable and terminal rows. */
    default DetectionEventClaim claim(SecurityEvent event) {
        return recordIfNew(event) ? DetectionEventClaim.NEW : DetectionEventClaim.COMPLETED;
    }

    default DetectionEventClaim claim(SecurityEvent event, Integer partition, Long offset,
                                      String routingKey) {
        return recordIfNew(event, partition, offset, routingKey)
                ? DetectionEventClaim.NEW : DetectionEventClaim.COMPLETED;
    }

    /** Mark all durable effects for an event as committed. */
    default void markCompleted(String eventId) {
        // In-memory/unit-test implementations may not need a second phase.
    }

    /** Mark an accepted event as terminally persisted to DLQ. */
    default void markDeadLettered(String eventId, String reason) {
        // Optional for source-compatible test stores.
    }

    default void recordDeadLettered(String eventId, String raw, Integer partition, Long offset,
                                    String reason) {
        markDeadLettered(eventId, reason);
    }

    default long pendingCount() {
        return 0L;
    }

    /** Pending rows left by a previous process are replayable after assignment. */
    default List<SecurityEvent> pendingForPartitions(Set<Integer> partitions, Duration window) {
        return List.of();
    }

    default List<PendingDetectionEvent> pendingRecordsForPartitions(Set<Integer> partitions,
                                                                     Duration window) {
        return pendingForPartitions(partitions, window).stream()
                .map(event -> new PendingDetectionEvent(event, null, null))
                .toList();
    }

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

    /** Replay in bounded batches so large journals do not require one giant list. */
    default void replayRecent(Duration window, Consumer<List<SecurityEvent>> batchConsumer) {
        List<SecurityEvent> events = recent(window);
        if (!events.isEmpty()) batchConsumer.accept(events);
    }

    /** Replay owned partition state in bounded batches and Kafka order. */
    default void replayRecentForPartitions(Set<Integer> partitions, Duration window,
                                           Consumer<List<SecurityEvent>> batchConsumer) {
        List<SecurityEvent> events = recentForPartitions(partitions, window);
        if (!events.isEmpty()) batchConsumer.accept(events);
    }

    /** Human-readable configured replay window for health/operations output. */
    default String recoveryWindow() {
        return "unknown";
    }
}
