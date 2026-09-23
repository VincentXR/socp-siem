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
