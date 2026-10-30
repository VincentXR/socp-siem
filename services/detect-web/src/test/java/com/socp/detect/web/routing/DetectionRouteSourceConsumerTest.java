package com.socp.detect.web.routing;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionRouteSourceConsumerTest {
    private static final TopicPartition PARTITION = new TopicPartition("canonical-test", 0);

    @Test
    void commitsOneContiguousOffsetPerPartitionAfterEveryDurableRoute() {
        DetectionRouteOutboxService routes = mock(DetectionRouteOutboxService.class);
        Consumer<String, String> session = session();
        DetectionRouteSourceConsumer source = source(routes);
        TopicPartition other = new TopicPartition("canonical-test", 1);
        var records = new ConsumerRecords<>(Map.of(
                PARTITION, List.of(new ConsumerRecord<>("canonical-test", 0, 12, "key", "a"),
                        new ConsumerRecord<>("canonical-test", 0, 13, "key", "b")),
                other, List.of(new ConsumerRecord<>("canonical-test", 1, 23, "key", "c"))));
        source.routePoll(session, records);
        var order = inOrder(routes, session);
        for (var record : records) {
            order.verify(routes).route(record.topic(), record.partition(), record.offset(), record.value());
        }
        order.verify(session).commitSync(Map.of(PARTITION, new OffsetAndMetadata(14),
                other, new OffsetAndMetadata(24)));
        verify(session, times(1)).commitSync(any(Map.class));
    }

    @Test
    void failedMiddleRecordLeavesEntirePollReplayableAndNeverSkipsIt() {
        DetectionRouteOutboxService routes = mock(DetectionRouteOutboxService.class);
        Consumer<String, String> session = session();
        var records = new ConsumerRecords<>(Map.of(PARTITION, List.of(
                new ConsumerRecord<>("canonical-test", 0, 12, "key", "a"),
                new ConsumerRecord<>("canonical-test", 0, 13, "key", "b"),
                new ConsumerRecord<>("canonical-test", 0, 14, "key", "c"))));
        when(routes.route("canonical-test", 0, 13, "b"))
                .thenThrow(new IllegalStateException("durable route failed")).thenReturn(null);
        DetectionRouteSourceConsumer source = source(routes);
        assertThrows(IllegalStateException.class, () -> source.routePoll(session, records));
        verify(session, never()).commitSync(any(Map.class));
        verify(routes, never()).route("canonical-test", 0, 14, "c");
        source.routePoll(session, records);
        verify(routes, times(2)).route("canonical-test", 0, 12, "a");
        verify(session).commitSync(Map.of(PARTITION, new OffsetAndMetadata(15)));
    }

    @Test
    void brokerCommitFailureEscapesForSessionReplayAndEmptyPollNeverCommits() {
        DetectionRouteOutboxService routes = mock(DetectionRouteOutboxService.class);
        Consumer<String, String> session = session();
        DetectionRouteSourceConsumer source = source(routes);
        source.routePoll(session, ConsumerRecords.empty());
        verify(session, never()).commitSync(any(Map.class));
        doThrow(new IllegalStateException("commit failed")).when(session).commitSync(any(Map.class));
        assertThrows(IllegalStateException.class, () -> source.routePoll(session, records()));
        verify(routes).route("canonical-test", 0, 12, "{}");
    }

    private static DetectionRouteSourceConsumer source(DetectionRouteOutboxService routes) {
        return new DetectionRouteSourceConsumer(routes, "unused:9092", "canonical-test", "route-test", false);
    }

    @Test
    void repeatedRoutingFailuresBackOffAcrossNewSessionsUntilDurableProgress() throws Exception {
        DetectionRouteOutboxService routes = mock(DetectionRouteOutboxService.class);
        AtomicInteger routed = new AtomicInteger();
        when(routes.route("canonical-test", 0, 12, "{}"))
                .thenAnswer(invocation -> {
                    if (routed.incrementAndGet() <= 3) throw new IllegalStateException("database unavailable");
                    return null;
                });
        List<Long> delays = new ArrayList<>();
        List<Consumer<String, String>> sessions = new ArrayList<>();
        AtomicReference<DetectionRouteSourceConsumer> source = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        DetectionRouteSourceConsumer consumer = new DetectionRouteSourceConsumer(
                routes, "unused:9092", "canonical-test", "route-test", true, properties -> {
                    Consumer<String, String> session = session();
                    sessions.add(session);
                    // Empty assignment polls must not reset the failure backoff.
                    when(session.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty(), records())
                            .thenThrow(new IllegalStateException("session lost after acknowledgement"));
                    return session;
                }, delay -> {
                    delays.add(delay);
                    if (delays.size() == 4) {
                        source.get().stop();
                        done.countDown();
                    }
                });
        source.set(consumer);
        try {
            consumer.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(250L, 500L, 1000L, 250L), delays);
            for (int i = 0; i < 3; i++) verify(sessions.get(i), never()).commitSync(any(Map.class));
            var order = inOrder(routes, sessions.get(3));
            order.verify(routes, times(4)).route("canonical-test", 0, 12, "{}");
            order.verify(sessions.get(3)).commitSync(Map.of(PARTITION, new OffsetAndMetadata(13)));
        } finally {
            consumer.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String, String> session() { return mock(Consumer.class); }

    private static ConsumerRecords<String, String> records() {
        return new ConsumerRecords<>(Map.of(PARTITION, List.of(
                new ConsumerRecord<>("canonical-test", 0, 12, "key", "{}"))));
    }
}
