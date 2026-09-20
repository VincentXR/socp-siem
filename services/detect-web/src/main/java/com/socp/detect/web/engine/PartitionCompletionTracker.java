package com.socp.detect.web.engine;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the completed prefix of records actually delivered by each partition.
 * Kafka offsets can have gaps (transaction markers and aborted records), but a
 * delivered, still-pending record must always block later completions.
 */
public final class PartitionCompletionTracker {

    private final Map<Integer, State> states = new ConcurrentHashMap<>();
    private final Map<Integer, Long> epochs = new ConcurrentHashMap<>();

    public synchronized long register(int partition, long offset) {
        State state = states.computeIfAbsent(partition, ignored -> new State(
                epochs.merge(partition, 1L, Long::sum)));
        if (state.nextExpected < 0) state.nextExpected = offset;
        if (offset >= state.nextExpected) state.seen.add(offset);
        return state.epoch;
    }

    public synchronized void complete(int partition, long offset) {
        State state = states.get(partition);
        if (state != null) complete(partition, offset, state.epoch);
    }

    public synchronized void complete(int partition, long offset, long epoch) {
        State state = states.get(partition);
        if (state == null || state.epoch != epoch) return;
        if (state.seen.contains(offset)) state.completed.add(offset);
        advance(state);
    }

    /** Return candidates without acknowledging them as committed. */
    public synchronized Map<TopicPartition, OffsetAndMetadata> ready(String topic) {
        Map<TopicPartition, OffsetAndMetadata> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, State> entry : states.entrySet()) {
            State state = entry.getValue();
            advance(state);
            if (state.pendingCommit >= 0) {
                out.put(new TopicPartition(topic, entry.getKey()),
                        new OffsetAndMetadata(state.pendingCommit));
            }
        }
        return out;
    }

    public synchronized void acknowledge(Map<TopicPartition, OffsetAndMetadata> committed) {
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            State state = states.get(entry.getKey().partition());
            if (state == null) continue;
            long offset = entry.getValue().offset();
            if (state.pendingCommit == offset) {
                state.nextExpected = offset;
                state.pendingCommit = -1;
                state.seen.removeIf(value -> value < offset);
                state.completed.removeIf(value -> value < offset);
            }
        }
    }

    public synchronized void remove(int partition) {
        states.remove(partition);
        epochs.merge(partition, 1L, Long::sum);
    }

    public synchronized int pendingOffsets(int partition) {
        State state = states.get(partition);
        return state == null ? 0 : state.seen.size();
    }

    public synchronized Set<Integer> partitions() {
        return Set.copyOf(states.keySet());
    }

    private static void advance(State state) {
        if (state.nextExpected < 0 || state.pendingCommit >= 0) return;
        long candidate = -1;
        while (!state.seen.isEmpty() && state.completed.remove(state.seen.first())) {
            candidate = state.seen.pollFirst() + 1;
        }
        if (candidate >= 0) {
            state.nextExpected = candidate;
            state.pendingCommit = candidate;
        }
    }

    private static final class State {
        final long epoch;
        long nextExpected = -1;
        long pendingCommit = -1;
        final TreeSet<Long> seen = new TreeSet<>();
        final Set<Long> completed = new TreeSet<>();

        State(long epoch) {
            this.epoch = epoch;
        }
    }
}
