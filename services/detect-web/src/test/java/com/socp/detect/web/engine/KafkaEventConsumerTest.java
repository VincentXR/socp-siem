package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.store.DetectionEventClaim;
import com.socp.detect.web.store.DetectionStateStore;
import com.socp.rule.model.SecurityEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaEventConsumerTest {

    @Mock
    private DetectEngineService engine;

    @Mock
    private DetectionStateStore stateStore;

    @Test
    void duplicateCompletedEventIdIsSubmittedOnlyOnce() {
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class)))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        String event = "{\"eventId\":\"consumer-test-100\",\"source\":\"auth\",\"host\":\"web-1\",\"msg\":\"login failed\"}";

        consumer.processRecord("key-1", event);
        consumer.processRecord("key-2", event);

        verify(engine, times(1)).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void malformedEventIsSentToDlqWithoutReachingEngine() {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(
                new AbstractMap.SimpleEntry<>(eventId, raw)));
        String malformed = "{not-json";

        consumer.processRecord("bad-key", malformed);

        assertEquals(1, dlq.size());
        assertEquals(null, dlq.get(0).getKey());
        assertEquals(malformed, dlq.get(0).getValue());
        verify(engine, times(0)).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void transientEngineFailureRemainsPendingAndIsNotCommittedToDlq() {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(
                new AbstractMap.SimpleEntry<>(eventId, raw)));
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        String event = "{\"eventId\":\"consumer-test-101\",\"source\":\"auth\","
                + "\"host\":\"web-1\",\"msg\":\"login failed\"}";

        consumer.processRecord("key-1", event);

        assertEquals(0, dlq.size());
        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void kafkaOwnershipMetadataIsPersistedWithTheEventClaim() {
        given(stateStore.claim(any(SecurityEvent.class), eq(2), eq(42L), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class)))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);

        consumer.processRecord(2, 42L, "default|src_ip|198.51.100.9",
                "{\"eventId\":\"partition-test-1\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\","
                        + "\"fields\":{\"src_ip\":\"198.51.100.9\"}}");

        verify(stateStore).claim(any(SecurityEvent.class), eq(2), eq(42L),
                eq("default|src_ip|198.51.100.9"));
        verify(stateStore).markCompleted("partition-test-1");
        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }
}
