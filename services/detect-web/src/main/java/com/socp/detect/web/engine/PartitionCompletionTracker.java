package com.socp.detect.web.engine;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-partition completed offsets and exposes only contiguous commit
 * candidates. Completion of offset 102 never advances a partition past a
 * still-pending offset 101.
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
        if (state.nextExpected < 0) state.nextExpected = offset;
        if (offset >= state.nextExpected) state.completed.add(offset);
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
        long cursor = state.nextExpected;
        boolean advanced = false;
        while (state.completed.remove(cursor)) {
            state.seen.remove(cursor);
            cursor++;
            advanced = true;
        }
        if (advanced) {
            state.nextExpected = cursor;
            state.pendingCommit = cursor;
        }
    }

    private static final class State {
        final long epoch;
        long nextExpected = -1;
        long pendingCommit = -1;
        Set<Long> seen = new TreeSet<>();
        Set<Long> completed = new TreeSet<>();

        State(long epoch) {
            this.epoch = epoch;
        }
    }
}
