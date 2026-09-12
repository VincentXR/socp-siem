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
