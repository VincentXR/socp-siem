package com.socp.search.config.infrastructure.kafka;

import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionOutboxPublisherTest {

    private IngestionOutboxPublisher publisher;

    @AfterEach
    void stopPublisher() {
        if (publisher != null) publisher.stop();
    }

    @Test void productionBatchSettlesOnlyAcknowledgedHeadsAfterBrokerCalls() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var first = pending("batch-good", "key-a", "{}", null);
        var second = pending("batch-retry", "key-b", "{}", null);
        var rows = List.of(first, second);
        var config = new com.socp.search.config.config.IngestRuntimeProperties();
        config.getOutbox().setMaxDrainRounds(1);
        assertEquals(8, config.getOutbox().getDeliveryConcurrency());
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        when(batches.claimIndependent(eq(rows), anyString(), eq(12), any())).thenReturn(rows);
        when(producer.sendAsync(eq("key-a"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        when(producer.sendAsync(eq("key-b"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(false));
        when(batches.markAcknowledged(eq(List.of(first)), anyString(), any())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer, null, config);
        publisher.configureBatchService(batches);

        TenantContext.runAsSystem(publisher::publish);

        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        var order = org.mockito.Mockito.inOrder(batches, producer, repository);
        order.verify(batches).claimIndependent(eq(rows), token.capture(), eq(12), any());
        order.verify(producer, org.mockito.Mockito.times(2)).sendAsync(any(), any(), any());
        order.verify(repository).scheduleRetry(eq(second.getId()), any(), any(), any(), eq(token.getValue()));
        order.verify(batches).markAcknowledged(eq(List.of(first)), eq(token.getValue()), any());
        verify(repository, never()).claim(any(), any(), anyInt(), anyInt(), anyString());
        verify(repository, never()).markPublished(any(), any(), any());
    }

    @Test
    void independentKeysUseBoundedClaimsAndFinalizeOnlyBrokerAcknowledgements() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> pending("batch-" + i, "key-" + i, "payload-" + i, null)).toList();
        rows.get(2).setAttempts(11);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        var sizes = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var claimed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var acknowledged = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var claimTokens = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var firstWave = new java.util.concurrent.CountDownLatch(8);
        when(batches.claimIndependent(any(), anyString(), eq(12), any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            List<IngestionOutboxEvent> events = invocation.getArgument(0);
            sizes.add(events.size());
            String token = invocation.getArgument(1);
            events.forEach(event -> {
                claimed.add(event.getPayload());
                claimTokens.put(event.getPayload(), token);
            });
            return events;
        });
        when(producer.sendAsync(any(), any(), any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            String payload = invocation.getArgument(1);
            org.junit.jupiter.api.Assertions.assertTrue(claimed.contains(payload));
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                firstWave.countDown();
                org.junit.jupiter.api.Assertions.assertTrue(firstWave.await(2, java.util.concurrent.TimeUnit.SECONDS));
                if (payload.equals("payload-2")) return java.util.concurrent.CompletableFuture.completedFuture(false);
                acknowledged.add(payload);
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            } finally {
                active.decrementAndGet();
            }
        });
        when(batches.markAcknowledged(any(), anyString(), any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            List<IngestionOutboxEvent> events = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertTrue(events.stream().allMatch(event -> acknowledged.contains(event.getPayload())));
            String token = invocation.getArgument(1);
            events.forEach(event -> assertEquals(claimTokens.get(event.getPayload()), token));
            return events.size();
        });
        publisher = new IngestionOutboxPublisher(repository, producer, new SimpleMeterRegistry(),
                8, 12, 60_000, 100, 1);
        publisher.configureBatchService(batches);

        TenantContext.runAsSystem(publisher::publish);

        assertEquals(List.of(1, 32, 32), sizes.stream().sorted().toList());
        assertEquals(8, peak.get());
        assertEquals(64, acknowledged.size());
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(batches, org.mockito.Mockito.times(3)).claimIndependent(any(), token.capture(), eq(12), any());
        verify(repository).markDead(eq(rows.get(2).getId()), any(), any(), eq(claimTokens.get("payload-2")));
        verify(repository, never()).releaseUnsentClaim(any(), any(), any(), any());
        verify(repository, never()).claim(any(), any(), anyInt(), anyInt(), anyString());
        verify(repository, never()).markPublished(any(), any(), anyString());
        verify(batches, org.mockito.Mockito.times(3)).markAcknowledged(any(), anyString(), any());
    }

    @Test
    void independentCohortsShareTwoPermitsAcrossTenantsAndJoinBeforeTheNextSelection() throws Exception {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 96).mapToObj(i -> {
            var row = pending("parallel-" + i, "key-" + i % 32, "payload-" + i, null);
            row.setTenantId("tenant-" + i / 32);
            return row;
        }).toList();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows, List.of());
        var claims = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var tokens = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var activeCohorts = new java.util.concurrent.atomic.AtomicInteger();
        var peakCohorts = new java.util.concurrent.atomic.AtomicInteger();
        var firstTwoClaims = new java.util.concurrent.CountDownLatch(2);
        var releaseClaims = new java.util.concurrent.CountDownLatch(1);
        var otherCohortsFinalized = new java.util.concurrent.CountDownLatch(2);
        var firstReceipt = new java.util.concurrent.CompletableFuture<Boolean>();
        var published = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            List<IngestionOutboxEvent> cohort = invocation.getArgument(0);
            String tenant = cohort.getFirst().getTenantId();
            assertEquals(tenant, TenantContext.require());
            assertEquals(32, cohort.size());
            claims.add(tenant);
            tokens.put(tenant, invocation.getArgument(1));
            peakCohorts.accumulateAndGet(activeCohorts.incrementAndGet(), Math::max);
            if (!tenant.equals("tenant-2")) {
                firstTwoClaims.countDown();
                assertTrue(releaseClaims.await(2, java.util.concurrent.TimeUnit.SECONDS));
            }
            return cohort;
        });
        when(producer.sendAsync(any(), any(), any())).thenAnswer(invocation -> {
            int index = Integer.parseInt(((String) invocation.getArgument(1)).substring("payload-".length()));
            assertEquals("tenant-" + index / 32, TenantContext.require());
            return index < 32 ? firstReceipt : java.util.concurrent.CompletableFuture.completedFuture(true);
        });
        when(batches.markAcknowledged(any(), anyString(), any())).thenAnswer(invocation -> {
            List<IngestionOutboxEvent> cohort = invocation.getArgument(0);
            String tenant = cohort.getFirst().getTenantId();
            assertEquals(tenant, TenantContext.require());
            assertEquals(tokens.get(tenant), invocation.getArgument(1));
            cohort.forEach(row -> published.add(row.getId()));
            activeCohorts.decrementAndGet();
            if (!tenant.equals("tenant-0")) otherCohortsFinalized.countDown();
            return cohort.size();
        });
        publisher = new IngestionOutboxPublisher(repository, producer, null, 8, 12, 60_000, 100, 1, 2, 5_000);
        publisher.configureBatchService(batches);
        try (var runner = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var drain = runner.submit(() -> TenantContext.runAsSystem(publisher::publish));
            try {
                assertTrue(firstTwoClaims.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(2, claims.size());
                releaseClaims.countDown();
                assertTrue(otherCohortsFinalized.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(64, published.size());
                assertFalse(drain.isDone());
                TenantContext.runAsSystem(publisher::publish);
                verify(repository).findDueOrderedBatch(any());
                firstReceipt.complete(true);
                drain.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                releaseClaims.countDown();
                firstReceipt.complete(true);
            }
        }
        assertEquals(2, peakCohorts.get());
        assertEquals(0, activeCohorts.get());
        assertEquals(96, published.size());
        verify(repository, org.mockito.Mockito.times(2)).findDueOrderedBatch(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void deadlineOrShutdownDoesNotClaimAWaitingCohortAndStillJoinsAdmittedReceipts(boolean shutdown) throws Exception {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 96).mapToObj(i -> {
            var row = pending("waiting-" + i, "key-" + i, "payload-" + i, null);
            row.setAttempts(11);
            return row;
        }).toList();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        var tokens = new java.util.concurrent.ConcurrentHashMap<String, String>();
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            List<IngestionOutboxEvent> cohort = invocation.getArgument(0);
            cohort.forEach(row -> tokens.put(row.getId(), invocation.getArgument(1)));
            return cohort;
        });
        var receipt = new java.util.concurrent.CompletableFuture<Boolean>();
        var submitted = new java.util.concurrent.CountDownLatch(64);
        when(producer.sendAsync(any(), any(), any())).thenAnswer(invocation -> {
            submitted.countDown();
            return receipt;
        });
        when(batches.markAcknowledged(any(), anyString(), any())).thenAnswer(invocation -> {
            List<IngestionOutboxEvent> cohort = invocation.getArgument(0);
            cohort.forEach(row -> assertEquals(tokens.get(row.getId()), invocation.getArgument(1)));
            return cohort.size();
        });
        publisher = new IngestionOutboxPublisher(repository, producer, null, 8, 12, 60_000, 100, 1, 1, 500);
        publisher.configureBatchService(batches);
        try (var runner = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var drain = runner.submit(() -> TenantContext.runAsSystem(publisher::publish));
            try {
                assertTrue(submitted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                if (shutdown) publisher.stop();
                else Thread.sleep(550);
                assertFalse(drain.isDone());
                verify(batches, org.mockito.Mockito.times(2)).claimIndependent(any(), anyString(), anyInt(), any());
                receipt.complete(true);
                drain.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally { receipt.complete(true); }
        }
        assertEquals(64, tokens.size());
        assertTrue(rows.subList(64, 96).stream().noneMatch(row -> tokens.containsKey(row.getId())));
        verify(batches, org.mockito.Mockito.times(2)).markAcknowledged(any(), anyString(), any());
        verify(repository, never()).releaseUnsentClaim(any(), any(), any(), any());
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), any());
        verify(repository, never()).markDead(any(), any(), any(), any());
    }

    @Test
    void failedCohortDoesNotAbandonAnotherCohortsStartedReceipts() throws Exception {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> pending("failure-" + i, "key-" + i, "payload-" + i, null)).toList();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        var tokens = new java.util.concurrent.ConcurrentHashMap<String, String>();
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            List<IngestionOutboxEvent> cohort = invocation.getArgument(0);
            cohort.forEach(row -> tokens.put(row.getId(), invocation.getArgument(1)));
            return cohort;
        });
        var failedReceipt = new java.util.concurrent.CompletableFuture<Boolean>();
        var heldReceipt = new java.util.concurrent.CompletableFuture<Boolean>();
        var submitted = new java.util.concurrent.CountDownLatch(64);
        var retried = new java.util.concurrent.CountDownLatch(32);
        when(producer.sendAsync(any(), any(), any())).thenAnswer(invocation -> {
            int index = Integer.parseInt(((String) invocation.getArgument(1)).substring("payload-".length()));
            submitted.countDown();
            return index < 32 ? failedReceipt : heldReceipt;
        });
        when(repository.scheduleRetry(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertEquals(tokens.get(invocation.getArgument(0)), invocation.getArgument(4));
            retried.countDown();
            return 1;
        });
        when(batches.markAcknowledged(any(), anyString(), any())).thenAnswer(invocation -> {
            List<IngestionOutboxEvent> cohort = invocation.getArgument(0);
            cohort.forEach(row -> assertEquals(tokens.get(row.getId()), invocation.getArgument(1)));
            return cohort.size();
        });
        publisher = new IngestionOutboxPublisher(repository, producer, null, 8, 12, 60_000, 100, 1, 1, 5_000);
        publisher.configureBatchService(batches);
        try (var runner = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var drain = runner.submit(() -> TenantContext.runAsSystem(publisher::publish));
            try {
                assertTrue(submitted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                failedReceipt.completeExceptionally(new IllegalStateException("broker receipt failed"));
                assertTrue(retried.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(drain.isDone());
                verify(batches, never()).markAcknowledged(any(), anyString(), any());
                heldReceipt.complete(true);
                drain.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                failedReceipt.complete(false);
                heldReceipt.complete(true);
            }
        }
        verify(batches).markAcknowledged(eq(rows.subList(32, 64)), eq(tokens.get(rows.get(32).getId())), any());
        verify(repository, org.mockito.Mockito.times(32)).scheduleRetry(any(), any(), any(), any(), any());
        verify(repository, never()).releaseUnsentClaim(any(), any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void independentCohortPipelinesThirtyTwoReceiptsAndJoinsThemEvenAfterShutdown(boolean exceptionalReceipt) throws Exception {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> pending("async-" + i, "key-" + i, "payload-" + i, null)).toList();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenReturn(rows);
        when(batches.markAcknowledged(any(), anyString(), any())).thenReturn(31);
        var receipts = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>>();
        var submitted = new java.util.concurrent.CountDownLatch(32);
        var cleanup = new AtomicBoolean();
        when(producer.sendAsync(any(), any(), any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            var receipt = new java.util.concurrent.CompletableFuture<Boolean>();
            receipts.put(invocation.getArgument(1), receipt);
            if (cleanup.get()) receipt.complete(false);
            submitted.countDown();
            return receipt;
        });
        var registry = new SimpleMeterRegistry();
        publisher = new IngestionOutboxPublisher(repository, producer, registry, 8, 12, 60_000, 100, 1);
        publisher.configureBatchService(batches);
        try (var runner = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var drain = runner.submit(() -> TenantContext.runAsSystem(publisher::publish));
            try {
                org.junit.jupiter.api.Assertions.assertTrue(submitted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(32, receipts.size());
                org.junit.jupiter.api.Assertions.assertFalse(drain.isDone());
                publisher.stop();
                if (exceptionalReceipt) receipts.get("payload-0").completeExceptionally(new IllegalStateException("failed receipt"));
                else receipts.get("payload-0").complete(false);
                for (int i = 1; i < 31; i++) receipts.get("payload-" + i).complete(true);
                verify(repository, never()).scheduleRetry(any(), any(), any(), any(), any());
                verify(batches, never()).markAcknowledged(any(), anyString(), any());
                org.junit.jupiter.api.Assertions.assertFalse(drain.isDone());
                receipts.get("payload-31").complete(true);
                drain.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                cleanup.set(true);
                receipts.values().forEach(receipt -> receipt.complete(false));
            }
        }
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(batches).claimIndependent(eq(rows), token.capture(), anyInt(), any());
        verify(repository).scheduleRetry(eq(rows.getFirst().getId()), any(), any(), any(), eq(token.getValue()));
        verify(batches).markAcknowledged(eq(rows.subList(1, 32)), eq(token.getValue()), any());
        verify(repository, never()).releaseUnsentClaim(any(), any(), any(), any());
        verify(producer, never()).sendAndAwait(any(), any(), any());
        assertEquals(1, operationCount(registry, "receipt_wait", exceptionalReceipt ? "failure" : "success"));
    }

    @Test
    void admittedClaimsFinishWhenTheDrainDeadlineExpiresDuringClaiming() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = List.of(pending("a", "key-a", "a", null), pending("b", "key-b", "b", null));
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            Thread.sleep(100);
            return rows;
        });
        when(producer.sendAsync(any(), any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        when(batches.markAcknowledged(any(), anyString(), any())).thenReturn(2);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 2, 12, 60_000, 100, 1, 1, 10);
        publisher.configureBatchService(batches);

        publisher.publish();

        verify(producer, org.mockito.Mockito.times(2)).sendAsync(any(), any(), any());
        verify(batches).markAcknowledged(org.mockito.ArgumentMatchers.argThat(
                events -> events.size() == rows.size() && events.containsAll(rows)), anyString(), any());
    }

    @Test
    void claimLatencyReleasesEveryUnsentClaimWithoutExhaustingItsLastAttempt() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> pending("expired-" + i, "key-" + i, "payload-" + i, null)).toList();
        rows.forEach(row -> row.setAttempts(11));
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            Thread.sleep(50);
            return rows;
        });
        publisher = new IngestionOutboxPublisher(repository, producer, null, 8, 12, 60_000, 100, 1) {
            @Override long deliveryAdmissionBudgetNanos() {
                return java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);
            }
        };
        publisher.configureBatchService(batches);

        publisher.publish();

        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(batches).claimIndependent(any(), token.capture(), anyInt(), any());
        for (var row : rows) {
            verify(repository).releaseUnsentClaim(eq(row.getId()), eq(row.getTenantId()), any(), eq(token.getValue()));
        }
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), any());
        verify(repository, never()).markDead(any(), any(), any(), any());
        verify(producer, never()).sendAsync(any(), any(), any());
        verify(batches, never()).markAcknowledged(any(), anyString(), any());
    }

    @Test
    void failedBatchClaimFallsBackToAuthoritativeIndividualClaims() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var row = pending("conflict", "key", "payload", null);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(List.of(row));
        when(batches.claimIndependent(any(), anyString(), anyInt(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("competing key owner"));
        when(repository.claim(eq(row.getId()), any(), eq(12), eq(0), anyString())).thenReturn(0);
        publisher = new IngestionOutboxPublisher(repository, producer);
        publisher.configureBatchService(batches);

        publisher.publish();

        verify(repository).claim(eq(row.getId()), any(), eq(12), eq(0), anyString());
        verify(producer, never()).sendAsync(any(), any(), any());
        verify(batches, never()).markAcknowledged(any(), anyString(), any());
    }

    @Test
    void shutdownAfterBatchClaimReturnsTheUnsentLastAttemptWithTheOriginalToken() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> pending("shutdown-" + i, "key-" + i, "payload-" + i, null)).toList();
        rows.forEach(row -> row.setAttempts(11));
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 8, 12, 60_000, 100, 1);
        publisher.configureBatchService(batches);
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            publisher.stop();
            return rows;
        });

        publisher.publish();

        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(batches).claimIndependent(any(), token.capture(), anyInt(), any());
        for (var row : rows) {
            verify(repository).releaseUnsentClaim(eq(row.getId()), eq(row.getTenantId()), any(), eq(token.getValue()));
        }
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), any());
        verify(repository, never()).markDead(any(), any(), any(), any());
        verify(producer, never()).sendAsync(any(), any(), any());
        verify(batches, never()).markAcknowledged(any(), anyString(), any());
    }

    @Test
    void shutdownDuringDeliveryFinalizesAcknowledgedRowsAndReleasesTheRemainingCohort() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> pending("partial-" + i, "key-" + i, "payload-" + i, null)).toList();
        rows.forEach(row -> row.setAttempts(11));
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        when(batches.claimIndependent(any(), anyString(), anyInt(), any())).thenReturn(rows);
        when(batches.markAcknowledged(any(), anyString(), any())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 1, 12, 60_000, 100, 1);
        publisher.configureBatchService(batches);
        when(producer.sendAsync(any(), any(), any())).thenAnswer(invocation -> {
            publisher.stop();
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        });

        publisher.publish();

        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(batches).claimIndependent(eq(rows), token.capture(), anyInt(), any());
        verify(producer).sendAsync(rows.getFirst().getRoutingKey(), rows.getFirst().getPayload(), null);
        verify(batches).markAcknowledged(eq(List.of(rows.getFirst())), eq(token.getValue()), any());
        verify(repository, never()).releaseUnsentClaim(eq(rows.getFirst().getId()), any(), any(), any());
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), any());
        verify(repository, never()).markDead(any(), any(), any(), any());
        for (var row : rows.subList(1, rows.size())) {
            verify(repository).releaseUnsentClaim(eq(row.getId()), eq(row.getTenantId()), any(), eq(token.getValue()));
        }
    }

    @Test
    void repeatedRoutingKeysRetainTheOrderedSingleRowPath() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var batches = mock(com.socp.search.config.service.IngestionOutboxBatchService.class);
        var rows = List.of(pending("a", "key", "a", null), pending("b", "key", "b", null));
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(rows);
        when(repository.claim(any(), any(), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(true);
        when(repository.markPublished(any(), any(), anyString())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer);
        publisher.configureBatchService(batches);

        publisher.publish();

        org.mockito.Mockito.verifyNoInteractions(batches);
        var order = org.mockito.Mockito.inOrder(producer, repository);
        order.verify(producer).sendAndAwait("key", "a", null);
        order.verify(repository).markPublished(eq(rows.getFirst().getId()), any(), anyString());
        order.verify(producer).sendAndAwait("key", "b", null);
    }

    @Test
    void slowHeadSelectionStillAdmitsTheFirstBoundedDeliveryBatch() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var event = pending("slow-selection", "hot-key", "{}", null);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any(Instant.class))).thenAnswer(invocation -> {
            // A real query on a hot-key backlog can outlast the delivery budget.
            Thread.sleep(300);
            return List.of(event);
        });
        when(repository.claim(any(), any(Instant.class), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(true);
        when(repository.markPublished(any(), any(Instant.class), anyString())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer, new SimpleMeterRegistry(),
                1, 12, 60_000, 100, 1, 1, 200);

        publisher.publish();

        verify(producer).sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent());
        verify(repository).markPublished(any(), any(Instant.class), anyString());
    }

    @Test
    void marksPublishedOnlyAfterBrokerAcknowledgement() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending(
                "event-1", "default|user|alice", "{}", "00-trace-01");
        when(repository.findDueOrderedBatch(any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent()))
                .thenReturn(true);
        when(repository.markPublished(any(), any(Instant.class), anyString())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository).markPublished(any(), any(Instant.class), anyString());
        verify(repository, never()).scheduleRetry(any(), any(Instant.class), any(), any(Instant.class), anyString());
    }

    @Test
    void releasesClaimWhenBrokerDoesNotAcknowledge() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending(
                "event-1", "route", "{}", null);
        when(repository.findDueOrderedBatch(any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository).scheduleRetry(any(), any(Instant.class),
                org.mockito.ArgumentMatchers.eq("Kafka broker did not acknowledge the event"), any(Instant.class), anyString());
        verify(repository, never()).markPublished(any(), any(Instant.class), anyString());
    }

    @Test
    void competingInstanceThatLosesClaimDoesNotPublish() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending("event-1", "route", "{}", null);
        when(repository.findDueOrderedBatch(any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt(), anyInt(), anyString())).thenReturn(0);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(producer, never()).sendAndAwait(any(), any(), any());
    }

    @Test
    void disabledKafkaDoesNotCreateAClaimReleaseWriteLoop() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();

        verify(repository, never()).recoverStaleBatch(any(), any(), eq(100));
        verify(repository, never())
                .findDueOrderedBatch(any());
    }

    @Test
    void throttlesStaleClaimRecoveryIndependentlyFromFastPolling() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any(Instant.class))).thenReturn(List.of());
        publisher = new IngestionOutboxPublisher(repository, producer);

        publisher.publish();
        publisher.publish();

        verify(repository).recoverStaleBatch(any(), any(), eq(100));
    }

    @Test
    void retryLimitMovesUnacknowledgedEventToDead() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        IngestionOutboxEvent event = pending("event-dead", "route", "{}", null);
        when(repository.findDueOrderedBatch(any(Instant.class))).thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), org.mockito.ArgumentMatchers.eq(1), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(false);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 1, 1, 60_000L, 100, 2);

        publisher.publish();

        verify(repository).markDead(any(),
                org.mockito.ArgumentMatchers.eq("Kafka broker did not acknowledge the event"), any(Instant.class), anyString());
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), anyString());
    }

    @Test
    void asyncTriggerRunsCrossTenantScanInsideSystemScope() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        AtomicBoolean systemScope = new AtomicBoolean();
        when(repository.markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100))).thenAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return 0;
        });
        when(repository.findDueOrderedBatch(any(Instant.class))).thenReturn(List.of());
        publisher = new IngestionOutboxPublisher(repository, producer);

        TenantContext.set("tenant-a");
        publisher.triggerAsync();

        verify(repository, org.mockito.Mockito.timeout(2_000))
                .markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100));
        org.junit.jupiter.api.Assertions.assertTrue(systemScope.get());
    }

    @Test
    void asyncDeliveryBindsEventTenantAndDoesNotDeadlockSingleWorker() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        IngestionOutboxEvent event = pending("event-async", "route", "{}", null);
        AtomicBoolean tenantScope = new AtomicBoolean();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.claim(any(), any(Instant.class), anyInt(), anyInt(), anyString())).thenAnswer(invocation -> {
            tenantScope.set("tenant-a".equals(TenantContext.get()) && !TenantContext.isSystemScope());
            return 1;
        });
        when(producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent()))
                .thenReturn(true);
        when(repository.markPublished(any(), any(Instant.class), anyString())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer);

        TenantContext.set("tenant-b");
        publisher.triggerAsync();

        verify(repository, org.mockito.Mockito.timeout(2_000)).markPublished(any(), any(Instant.class), anyString());
        org.junit.jupiter.api.Assertions.assertTrue(tenantScope.get());
    }

    @Test
    void reportsClaimBatchSeparatelyFromTheTrueBacklogAndOldestAge() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any(Instant.class))).thenReturn(List.of());
        when(repository.countByStatus("PENDING")).thenReturn(827L);
        when(repository.findOldestCreatedAtByStatus("PENDING"))
                .thenReturn(Instant.now().minusSeconds(120));
        when(repository.countByStatus("DEAD")).thenReturn(3L);
        when(repository.findOldestUpdatedAtByStatus("DEAD"))
                .thenReturn(Instant.now().minusSeconds(300));
        publisher = new IngestionOutboxPublisher(repository, producer, registry,
                1, 12, 60_000L, 100, 2);

        publisher.publish();

        assertEquals(0.0, registry.get("socp.ingestion.outbox.claim.batch.size").gauge().value());
        assertEquals(827.0, registry.get("socp.ingestion.outbox.pending.count").gauge().value());
        double oldestAge = registry.get("socp.ingestion.outbox.oldest.pending.age.seconds").gauge().value();
        org.junit.jupiter.api.Assertions.assertTrue(oldestAge >= 119 && oldestAge <= 121);
        assertEquals(3.0, registry.get("socp.ingestion.outbox.dead.count").gauge().value());
        double oldestDeadAge = registry.get("socp.ingestion.outbox.oldest.dead.age.seconds").gauge().value();
        org.junit.jupiter.api.Assertions.assertTrue(oldestDeadAge >= 299 && oldestDeadAge <= 301);
        for (String operation : List.of("selection", "exhausted", "stale", "backlog_metrics", "drain")) {
            assertEquals(1, operationCount(registry, operation, "success"));
        }
        publisher.publish();
        assertEquals(2, operationCount(registry, "selection", "success"));
        assertEquals(2, operationCount(registry, "drain", "success"));
        assertEquals(1, operationCount(registry, "stale", "success"));
    }

    @Test
    void refreshesBacklogMetricsWhenScanFails() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(producer.isEnabled()).thenReturn(true);
        when(repository.markExhaustedBatch(anyInt(), anyString(), any(Instant.class), eq(100)))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(repository.countByStatus("PENDING")).thenReturn(11L);
        when(repository.countByStatus("DEAD")).thenReturn(2L);
        publisher = new IngestionOutboxPublisher(repository, producer, registry,
                1, 12, 60_000L, 100, 2);

        publisher.publish();

        assertEquals(11.0, registry.get("socp.ingestion.outbox.pending.count").gauge().value());
        assertEquals(2.0, registry.get("socp.ingestion.outbox.dead.count").gauge().value());
        assertEquals(1, operationCount(registry, "exhausted", "failure"));
        assertEquals(1, operationCount(registry, "drain", "failure"));
        assertEquals(1, operationCount(registry, "backlog_metrics", "success"));
        assertEquals(0, operationCount(registry, "selection", "success"));
    }

    @Test
    void backlogQueryFailureIsMeasuredWithoutUndoingAnAcknowledgedPublication() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var registry = new SimpleMeterRegistry();
        var row = pending("metric-query-failure", "route", "{}", null);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(List.of(row));
        when(repository.claim(any(), any(), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(true);
        when(repository.markPublished(any(), any(), anyString())).thenReturn(1);
        when(repository.countByStatus("PENDING")).thenThrow(new IllegalStateException("metrics query failed"));
        publisher = new IngestionOutboxPublisher(repository, producer, registry, 1, 12, 60_000, 100, 1);

        publisher.publish();

        verify(repository).markPublished(eq(row.getId()), any(), anyString());
        assertEquals(1, operationCount(registry, "backlog_metrics", "failure"));
        assertEquals(1, operationCount(registry, "drain", "failure"));
    }

    @Test
    void operationTimerFailureCannotChangeAcknowledgedPublication() {
        var repository = mock(IngestionOutboxRepository.class);
        var producer = mock(KafkaEventProducer.class);
        var row = pending("timer-failure", "route", "{}", null);
        when(producer.isEnabled()).thenReturn(true);
        when(repository.findDueOrderedBatch(any())).thenReturn(List.of(row));
        when(repository.claim(any(), any(), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(true);
        when(repository.markPublished(any(), any(), anyString())).thenReturn(1);
        publisher = new IngestionOutboxPublisher(repository, producer, new SimpleMeterRegistry(), 1, 12, 60_000, 100, 1);
        var timer = mock(io.micrometer.core.instrument.Timer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("timer unavailable")).when(timer)
                .record(org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class));
        var timers = (io.micrometer.core.instrument.Timer[][])
                org.springframework.test.util.ReflectionTestUtils.getField(publisher, "operationTimers");
        for (var outcomes : timers) java.util.Arrays.fill(outcomes, timer);

        publisher.publish();

        verify(repository).markPublished(eq(row.getId()), any(), anyString());
        verify(repository, never()).scheduleRetry(any(), any(), any(), any(), any());
        verify(timer, org.mockito.Mockito.atLeastOnce()).record(org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    private static long operationCount(SimpleMeterRegistry registry, String operation, String outcome) {
        return registry.get("socp.ingestion.outbox.operation.duration")
                .tags("operation", operation, "outcome", outcome).timer().count();
    }

    @Test
    void drainsSequentialHeadsOfOneHotKeyWithoutWaitingForAnotherPoll() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        var remaining = new java.util.concurrent.ConcurrentLinkedQueue<IngestionOutboxEvent>();
        for (int i = 0; i < 10; i++) remaining.add(pending("event-" + i, "one-key", "payload-" + i, null));
        when(repository.findDueOrderedBatch(any(Instant.class))).thenAnswer(invocation ->
                remaining.isEmpty() ? List.of() : List.of(remaining.element()));
        when(repository.claim(any(), any(), anyInt(), anyInt(), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn(true);
        when(repository.markPublished(any(), any(), anyString())).thenAnswer(invocation -> {
            assertEquals(remaining.remove().getId(), invocation.getArgument(0));
            return 1;
        });
        publisher = new IngestionOutboxPublisher(repository, producer, null,
                1, 12, 60_000L, 100, 2, 20, 10_000L);

        publisher.publish();

        assertEquals(0, remaining.size());
        var order = org.mockito.Mockito.inOrder(producer);
        for (int i = 0; i < 10; i++) order.verify(producer).sendAndAwait("one-key", "payload-" + i, null);
        verify(repository, org.mockito.Mockito.times(11)).findDueOrderedBatch(any(Instant.class));
    }

    @Test
    void cleanupUsesConfiguredBoundedBatches() {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(repository.deletePublishedBatchBefore(any(Instant.class), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(100, 100, 100);
        publisher = new IngestionOutboxPublisher(repository, producer, null,
                1, 12, 60_000L, 100, 2);

        publisher.cleanupPublished();

        verify(repository, org.mockito.Mockito.times(2))
                .deletePublishedBatchBefore(any(Instant.class), org.mockito.ArgumentMatchers.eq(100));
    }

    private static IngestionOutboxEvent pending(String eventId, String routingKey,
                                                String payload, String traceparent) {
        IngestionOutboxEvent event = IngestionOutboxEvent.pending(eventId, routingKey, payload, traceparent);
        event.setTenantId("tenant-a");
        return event;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "retry", "dead"})
    void completionRetainsTheExactClaimToken(String outcome) {
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 1, 2, 60000, 100, 1);
        IngestionOutboxEvent row = pending("event-token", "route", "{}", null);
        row.setAttempts("dead".equals(outcome) ? 1 : 0);
        org.mockito.Mockito.when(repository.findDueOrderedBatch(any())).thenReturn(List.of(row));
        org.mockito.Mockito.when(repository.claim(eq(row.getId()), any(), eq(2), eq(row.getAttempts()), anyString())).thenReturn(1);
        when(producer.sendAndAwait(any(), any(), any())).thenReturn("success".equals(outcome));
        TenantContext.runAsSystem(publisher::publish);
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
        IngestionOutboxRepository repository = mock(IngestionOutboxRepository.class);
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        publisher = new IngestionOutboxPublisher(repository, producer, null, 1, 2, 60000, 100, 1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(repository.findDueOrderedBatch(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return List.of();
        });
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish));
        try {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(() -> TenantContext.runAsSystem(publisher::publish))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
            verify(repository).findDueOrderedBatch(any());
        } finally { release.countDown(); }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
