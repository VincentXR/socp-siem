package com.socp.detect.web.engine;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionCompletionTrackerTest {

    @Test
    void commitsDeliveredPrefixAcrossTransactionGapsAndPolls() {
        PartitionCompletionTracker tracker = new PartitionCompletionTracker();
        TopicPartition partition = new TopicPartition("routed", 0);
        tracker.register(0, 10);
        tracker.register(0, 12);
        tracker.complete(0, 10);
        var first = tracker.ready("routed");
        assertEquals(11, first.get(partition).offset());
        tracker.complete(0, 12);
        assertEquals(first, tracker.ready("routed"), "failed commit must remain retryable");
        tracker.acknowledge(first);
        var second = tracker.ready("routed");
        assertEquals(13, second.get(partition).offset());
        tracker.acknowledge(second);

        tracker.register(0, 17);
        tracker.complete(0, 17);
        assertEquals(18, tracker.ready("routed").get(partition).offset());
    }

    @Test
    void gapsDoNotAllowSkippingPendingDeliveredRecordsOrInventingCompletions() {
        PartitionCompletionTracker tracker = new PartitionCompletionTracker();
        tracker.register(0, 10);
        tracker.register(0, 12);
        tracker.complete(0, 11);
        tracker.complete(0, 12);
        tracker.complete(0, 12);
        assertTrue(tracker.ready("routed").isEmpty());
        assertEquals(2, tracker.pendingOffsets(0));
        tracker.complete(0, 10);
        assertEquals(13, tracker.ready("routed").get(new TopicPartition("routed", 0)).offset());
    }

    @Test
    void onlyContiguousOffsetsBecomeCommittable() {
        PartitionCompletionTracker tracker = new PartitionCompletionTracker();
        tracker.register(0, 100);
        tracker.register(0, 101);
        tracker.register(0, 102);

        tracker.complete(0, 101);
        assertTrue(tracker.ready("socp-events").isEmpty());

        tracker.complete(0, 102);
        assertTrue(tracker.ready("socp-events").isEmpty());

        tracker.complete(0, 100);
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> ready =
                tracker.ready("socp-events");
        assertEquals(1, ready.size());
        assertEquals(103L, ready.get(new TopicPartition("socp-events", 0)).offset());
    }

    @Test
    void failedCommitCanBeRetriedWithoutLosingCompletion() {
        PartitionCompletionTracker tracker = new PartitionCompletionTracker();
        tracker.register(1, 7);
        tracker.complete(1, 7);

        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> ready =
                tracker.ready("socp-events");
        assertEquals(8L, ready.get(new TopicPartition("socp-events", 1)).offset());
        assertEquals(8L, tracker.ready("socp-events")
                .get(new TopicPartition("socp-events", 1)).offset());

        tracker.acknowledge(ready);
        assertTrue(tracker.ready("socp-events").isEmpty());
    }

    @Test
    void staleCompletionFromARevokedAssignmentCannotAdvanceNewOwner() {
        PartitionCompletionTracker tracker = new PartitionCompletionTracker();
        long oldEpoch = tracker.register(2, 10);
        tracker.remove(2);
        long newEpoch = tracker.register(2, 20);

        tracker.complete(2, 10, oldEpoch);
        assertTrue(tracker.ready("socp-events").isEmpty());

        tracker.complete(2, 20, newEpoch);
        assertEquals(21L, tracker.ready("socp-events")
                .get(new TopicPartition("socp-events", 2)).offset());
    }
}
