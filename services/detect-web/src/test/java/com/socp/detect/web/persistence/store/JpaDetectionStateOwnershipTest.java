package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateOwnerEntity;
import com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaDetectionStateOwnershipTest {

    @Test
    void batchFenceReuseIsInvalidatedAtSavepointRollbackAndTransactionCompletion() {
        var repository = mock(DetectionStateOwnerRepository.class);
        var ownership = new JpaDetectionStateOwnership(repository, "node", Duration.ofSeconds(30));
        var leases = java.util.stream.IntStream.range(0, 6).mapToObj(partition ->
                new DetectionStateOwnership.Lease("events", partition, 0, "node", 1, Instant.now())).toList();
        var rows = leases.stream().map(lease -> {
            var row = mock(DetectionStateOwnerRepository.LockedFence.class);
            when(row.getOwnerKey()).thenReturn(lease.unitKey());
            when(row.getOwnerId()).thenReturn(lease.ownerId());
            when(row.getFencingEpoch()).thenReturn(lease.fencingEpoch());
            return row;
        }).toList();
        when(repository.lockFences(any())).thenReturn(rows);
        when(repository.renewLockedFences(any(), any(), any())).thenReturn(6);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            ownership.assertCurrentBatch(leases);
            ownership.assertCurrentBatch(leases);
            ownership.assertCurrent(leases.getFirst());
            verify(repository, org.mockito.Mockito.times(1)).lockFences(any());
            verify(repository, org.mockito.Mockito.never()).touch(anyString(), anyString(), anyLong(), any(), any());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.savepointRollback(new Object()));
            ownership.assertCurrentBatch(leases);
            verify(repository, org.mockito.Mockito.times(2)).lockFences(any());
            verify(repository, org.mockito.Mockito.times(2)).renewLockedFences(any(), any(), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(0));
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
        when(repository.touch(anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);
        ownership.assertCurrentBatch(leases);
        verify(repository, org.mockito.Mockito.times(6)).touch(anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void contradictoryTokensForOneOwnerAreRejectedBeforeAnyDatabaseWrite() {
        var repository = mock(DetectionStateOwnerRepository.class);
        var ownership = new JpaDetectionStateOwnership(repository, "node", Duration.ofSeconds(30));
        var current = new DetectionStateOwnership.Lease("events", 0, 0, "node", 2, Instant.now());
        var stale = new DetectionStateOwnership.Lease("events", 0, 0, "node", 1, Instant.now());
        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                () -> ownership.assertCurrentBatch(java.util.List.of(current, stale)));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void repeatedFenceChecksShareOnlyTheActiveTransaction() {
        var repository = mock(DetectionStateOwnerRepository.class);
        var ownership = new JpaDetectionStateOwnership(repository, "node", Duration.ofSeconds(30));
        var lease = new DetectionStateOwnership.Lease("events", 0, 0, "node", 1, Instant.now());
        when(repository.touch(anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            ownership.assertCurrent(lease);
            ownership.assertCurrent(lease);
            var outer = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            outer.forEach(org.springframework.transaction.support.TransactionSynchronization::suspend);
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            ownership.assertCurrent(lease);
            ownership.assertCurrent(lease);
            var inner = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            inner.forEach(sync -> sync.afterCompletion(0));
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            outer.forEach(sync -> {
                sync.resume();
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(sync);
            });
            ownership.assertCurrent(lease);
            verify(repository, org.mockito.Mockito.times(2)).touch(anyString(), anyString(), anyLong(), any(), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(0));
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
        ownership.assertCurrent(lease);
        ownership.assertCurrent(lease);
        verify(repository, org.mockito.Mockito.times(4)).touch(anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void firstClaimCreatesEpochOneAndTouchUsesExactToken() {
        DetectionStateOwnerRepository repository = mock(DetectionStateOwnerRepository.class);
        JpaDetectionStateOwnership ownership = new JpaDetectionStateOwnership(
                repository, "node-a", Duration.ofSeconds(30));
        when(repository.insert(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyLong(),
                any(Instant.class), any(Instant.class))).thenReturn(1);

        DetectionStateOwnership.Lease lease = ownership.acquire("socp-events", 3, 1);

        assertEquals("socp-events", lease.inputTopic());
        assertEquals(3, lease.partition());
        assertEquals(1, lease.shard());
        assertEquals(1L, lease.fencingEpoch());
        verify(repository).insert(eq(lease.unitKey()), eq("socp-events"), eq(3), eq(1),
                eq("node-a"), eq(1L), any(Instant.class), any(Instant.class));

        when(repository.touch(eq(lease.unitKey()), eq("node-a"), eq(1L),
                any(Instant.class), any(Instant.class))).thenReturn(1);
        ownership.assertCurrent(lease);
    }

    @Test
    void staleEpochIsRejectedBeforeDurableWork() {
        DetectionStateOwnerRepository repository = mock(DetectionStateOwnerRepository.class);
        JpaDetectionStateOwnership ownership = new JpaDetectionStateOwnership(
                repository, "node-a", Duration.ofSeconds(30));
        DetectionStateOwnership.Lease lease = new DetectionStateOwnership.Lease(
                "socp-events", 2, 0, "node-a", 4L, Instant.now().plusSeconds(30));
        when(repository.touch(anyString(), eq("node-a"), eq(4L),
                any(Instant.class), any(Instant.class))).thenReturn(0);

        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                () -> ownership.assertCurrent(lease));
    }

    @Test
    void existingClaimReturnsTheDatabaseFenceAfterTakeover() {
        DetectionStateOwnerRepository repository = mock(DetectionStateOwnerRepository.class);
        JpaDetectionStateOwnership ownership = new JpaDetectionStateOwnership(
                repository, "node-a", Duration.ofSeconds(30));
        String key = DetectionStateOwnership.unitKey("socp-events", 0, 0);
        Instant leaseUntil = Instant.now().plusSeconds(30);
        when(repository.claimExisting(eq(key), eq("node-a"), any(Instant.class), any(Instant.class)))
                .thenReturn(1);
        when(repository.findById(key)).thenReturn(Optional.of(new DetectionStateOwnerEntity(
                key, "socp-events", 0, 0, "node-a", 9L, leaseUntil, Instant.now())));

        DetectionStateOwnership.Lease lease = ownership.acquire("socp-events", 0, 0);

        assertEquals(9L, lease.fencingEpoch());
        assertEquals(leaseUntil, lease.leaseUntil());
    }

    @Test
    void releaseInvalidatesTheCurrentFence() {
        DetectionStateOwnerRepository repository = mock(DetectionStateOwnerRepository.class);
        JpaDetectionStateOwnership ownership = new JpaDetectionStateOwnership(
                repository, "node-a", Duration.ofSeconds(30));
        DetectionStateOwnership.Lease lease = new DetectionStateOwnership.Lease(
                "socp-events", 5, 0, "node-a", 3L, Instant.now().plusSeconds(30));

        ownership.release(lease);

        verify(repository).release(eq(lease.unitKey()), eq("node-a"), eq(3L), any(Instant.class));
    }
}
