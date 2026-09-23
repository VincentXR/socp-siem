package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionRouteOutboxPublisherUnitTest {
    @Mock private DetectionRouteOutboxRepository repository;
    @Mock private KafkaProducer<String, String> producer;
    private DetectionRouteOutboxPublisher publisher;
    private DetectionRouteOutboxEntity row;

    @BeforeEach
    void setUp() {
        publisher = new DetectionRouteOutboxPublisher(repository, "unused:9092", true, 2, "7d", 100, 1);
        ReflectionTestUtils.setField(publisher, "producer", producer);
        row = new DetectionRouteOutboxEntity("delivery-1", "tenant-a", "event-1", "v2", "plan-1",
                "STATELESS", "event", "event-1", "route-key", "canonical", 0, 12,
                "routed", "{}", Instant.now());
        when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class))).thenReturn(List.of(row));
    }

    @AfterEach
    void close() { publisher.close(); }

    @Test
    @SuppressWarnings("unchecked")
    void onlyBrokerAcknowledgementPublishesTheClaimAndPreservesDeliveryIdentity() {
        when(repository.claim(eq("delivery-1"), any(Instant.class), eq(0), eq(2))).thenReturn(1);
        CompletableFuture<RecordMetadata> acknowledgement = new CompletableFuture<>();
        when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            verify(repository, never()).markPublished(any(), anyInt(), anyInt(), any(Long.class), any());
            acknowledgement.complete(new RecordMetadata(new TopicPartition("routed", 3), 40, 2, 0, 0, 0));
            return acknowledgement;
        });

        publisher.publishDue();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(sent.capture());
        assertEquals("routed", sent.getValue().topic());
        assertEquals("route-key", sent.getValue().key());
        assertEquals("delivery-1", new String(sent.getValue().headers().lastHeader("socp-delivery-id").value(),
                StandardCharsets.UTF_8));
        verify(repository).markPublished(eq("delivery-1"), eq(1), eq(3), eq(42L), any(Instant.class));
        verify(repository, never()).markFailed(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void failedAcknowledgementReleasesOnlyItsAttemptWithBackoff() {
        when(repository.claim(eq("delivery-1"), any(Instant.class), eq(0), eq(2))).thenReturn(1);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        publisher.publishDue();

        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> updatedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markFailed(eq("delivery-1"), eq(1), eq("PENDING"), retryAt.capture(), any(), updatedAt.capture());
        assertTrue(retryAt.getValue().isAfter(updatedAt.getValue()));
        verify(repository, never()).markPublished(any(), anyInt(), anyInt(), any(Long.class), any());
    }

    @Test
    void failedFinalAttemptBecomesDead() {
        row.setAttempts(1);
        when(repository.claim(eq("delivery-1"), any(Instant.class), eq(1), eq(2))).thenReturn(1);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        publisher.publishDue();

        verify(repository).markFailed(eq("delivery-1"), eq(2), eq("DEAD"), any(), any(), any());
    }

    @Test
    void lostClaimNeverSends() {
        publisher.publishDue();

        verify(producer, never()).send(any());
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
