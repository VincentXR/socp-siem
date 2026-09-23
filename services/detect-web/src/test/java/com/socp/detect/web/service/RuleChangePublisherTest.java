package com.socp.detect.web.service;

import com.socp.detect.web.persistence.repository.RuleChangeOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuleChangePublisherTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void enqueuesTenantScopedChangeInsteadOfSendingInline() {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        TenantContext.set("tenant-a");

        publisher.publish("rule-7", "update");

        ArgumentCaptor<RuleChangeOutbox> row = ArgumentCaptor.forClass(RuleChangeOutbox.class);
        verify(repository).save(row.capture());
        assertEquals("tenant-a", row.getValue().getTenantId());
        assertEquals("rule-7", row.getValue().getRuleId());
        assertEquals("PENDING", row.getValue().getStatus());
    }

    @Test
    void retryLimitMovesRuleChangeToDeadWithDurableReason() {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        RuleChangeOutbox row = new RuleChangeOutbox();
        row.setId("outbox-1");
        row.setTenantId("tenant-a");
        row.setRuleId("rule-7");
        row.setAttempts(11);

        publisher.scheduleRetry(row, "claim-owner", new IllegalStateException("broker unavailable"));

        verify(repository).markDead(eq("outbox-1"),
                eq("IllegalStateException: broker unavailable"), any(), anyString());
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), anyString());
    }

    @Test
    void drainsMoreThanOnePendingBatchWithinOneWindow() {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        RuleChangeOutbox row = new RuleChangeOutbox();
        row.setId("outbox-backlog");
        row.setTenantId("tenant-a");
        row.setRuleId("rule-7");
        row.setAction("update");
        row.setAttempts(0);
        row.setCreatedAt(java.time.Instant.now());
        row.setNextAttemptAt(java.time.Instant.now());
        List<RuleChangeOutbox> fullBatch = java.util.Collections.nCopies(100, row);
        when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any())).thenReturn(fullBatch, fullBatch, List.of());
        when(repository.claim(any(), any(), anyInt(), anyInt(), anyString())).thenReturn(0);

        publisher.flush();

        verify(repository, org.mockito.Mockito.times(3))
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any());
    }

    @Test
    void apiRoleDoesNotDrainRuleChangeOutboxToKafka() {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "runtimeRole", "api");

        publisher.flush();

        verifyNoInteractions(repository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "retry", "dead"})
    void completionRetainsTheExactClaimToken(String outcome) {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "maxAttempts", 2);
        RuleChangeOutbox row = new RuleChangeOutbox();
        row.setId("token-row"); row.setRuleId("rule-a"); row.setAction("update");
        row.setTenantId("tenant-a"); row.setCreatedAt(java.time.Instant.now());
        row.setAttempts("dead".equals(outcome) ? 1 : 0);
        org.mockito.Mockito.when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));
        org.mockito.Mockito.when(repository.claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), anyString())).thenReturn(1);
        @SuppressWarnings("unchecked")
        var producer = (org.apache.kafka.clients.producer.KafkaProducer<String, String>) mock(org.apache.kafka.clients.producer.KafkaProducer.class);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "producer", producer);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "topic", "socp-rule-changes");
        when(producer.send(any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            assertEquals(false, TenantContext.isSystemScope());
            if (!"success".equals(outcome)) throw new IllegalStateException("broker unavailable");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        TenantContext.runAsSystem(publisher::flush);
        var owner = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), owner.capture());
        java.util.UUID.fromString(owner.getValue());
        switch (outcome) {
            case "success" -> verify(repository).markPublished(eq(row.getId()), any(), eq(owner.getValue()));
            case "retry" -> verify(repository).scheduleRetry(eq(row.getId()), any(), any(), any(), eq(owner.getValue()));
            case "dead" -> verify(repository).markDead(eq(row.getId()), any(), any(), eq(owner.getValue()));
            default -> throw new AssertionError(outcome);
        }
    }

    @Test
    void overlappingDrainsScanOnlyOnce() throws Exception {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "maxAttempts", 2);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return List.of();
        });
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::flush));
        try {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::flush))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
            verify(repository).findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any());
        } finally { release.countDown(); }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
