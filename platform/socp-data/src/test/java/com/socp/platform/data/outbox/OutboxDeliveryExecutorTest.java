package com.socp.platform.data.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDeliveryExecutorTest {
    @Test void capacityIsAcquiredBeforeInvokingTheClaimCallback() throws Exception {
        try (var executor = new OutboxDeliveryExecutor("outbox-test-", 2)) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var third = new CountDownLatch(1);
            var started = new AtomicInteger();
            CompletableFuture<Void> drain = CompletableFuture.runAsync(() -> executor.deliver(
                    List.of(1, 2, 3), System.nanoTime() + Duration.ofSeconds(10).toNanos(), item -> {
                        if (started.incrementAndGet() == 3) third.countDown();
                        entered.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(third.await(100, TimeUnit.MILLISECONDS));
                assertFalse(drain.isDone());
            } finally { release.countDown(); }
            drain.get(5, TimeUnit.SECONDS);
            assertEquals(3, started.get());
        }
    }

    @Test void expiredAdmissionWindowDoesNotConsumeAnAttempt() {
        try (var executor = new OutboxDeliveryExecutor("outbox-expired-", 1)) {
            AtomicInteger attempts = new AtomicInteger();
            executor.deliver(List.of(1, 2), System.nanoTime() - 1, item -> attempts.incrementAndGet());
            assertEquals(0, attempts.get());
        }
    }

    @Test void waitingForCapacityCannotAdmitAnotherClaimAfterTheDeadline() throws Exception {
        try (var executor = new OutboxDeliveryExecutor("outbox-deadline-", 1)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            var drain = CompletableFuture.runAsync(() -> executor.deliver(List.of(1, 2), deadline, item -> {
                attempts.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                assertFalse(drain.isDone(), "admitted delivery must still be joined");
            } finally { release.countDown(); }
            drain.get(5, TimeUnit.SECONDS);
            assertEquals(1, attempts.get());
        }
    }

    @Test void callbackFailureReleasesCapacityAndTheDrainJoinsWork() {
        try (var executor = new OutboxDeliveryExecutor("outbox-failed-", 1)) {
            assertThrows(java.util.concurrent.CompletionException.class, () -> executor.deliver(
                    List.of(1), System.nanoTime() + Duration.ofSeconds(5).toNanos(), item -> { throw new IllegalStateException("database unavailable"); }));
            AtomicInteger completed = new AtomicInteger();
            executor.deliver(List.of(2), System.nanoTime() + Duration.ofSeconds(5).toNanos(), item -> completed.incrementAndGet());
            assertEquals(1, completed.get());
        }
    }

    @Test void closedExecutorCannotClaimAndRejectsWithoutLeakingAPermit() {
        var executor = new OutboxDeliveryExecutor("outbox-closed-", 1);
        executor.close();
        AtomicInteger attempts = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> executor.deliver(
                    List.of(1), System.nanoTime() + Duration.ofSeconds(5).toNanos(), item -> attempts.incrementAndGet()));
        }
        assertEquals(0, attempts.get());
    }

    @Test void sameKeyIsFifoWhileAnotherKeyCanProgress() throws Exception {
        record Candidate(String key, int sequence) { }
        try (var executor = new OutboxDeliveryExecutor("outbox-keyed-", 2)) {
            var firstEntered = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var otherKeyCompleted = new CountDownLatch(1);
            var delivered = new CopyOnWriteArrayList<Candidate>();
            Candidate first = new Candidate("same", 1);
            Candidate second = new Candidate("same", 2);
            Candidate other = new Candidate("other", 1);

            var drain = CompletableFuture.runAsync(() -> executor.deliverKeyed(
                    List.of(first, second, other),
                    System.nanoTime() + Duration.ofSeconds(10).toNanos(), Candidate::key, candidate -> {
                        if (candidate == first) {
                            firstEntered.countDown();
                            try {
                                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("release timed out");
                                }
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                        }
                        delivered.add(candidate);
                        if (candidate == other) otherKeyCompleted.countDown();
                    }));
            try {
                assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
                assertTrue(otherKeyCompleted.await(2, TimeUnit.SECONDS));
                assertFalse(delivered.contains(second), "later same-key row must not overtake");
            } finally {
                releaseFirst.countDown();
            }
            drain.get(5, TimeUnit.SECONDS);
            assertTrue(delivered.indexOf(first) < delivered.indexOf(second));
        }
    }

    @Test void batchReservationsRemainHeldUntilDurableAcknowledgement() throws Exception {
        try (var executor = new OutboxDeliveryExecutor("batch-reserved-", 2)) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var acknowledging = new CountDownLatch(1);
            var releaseAcknowledgement = new CountDownLatch(1);
            var otherClaimed = new AtomicInteger();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            var first = CompletableFuture.runAsync(() -> executor.deliverClaimedBatch(List.of(1, 2), deadline,
                    List::copyOf, item -> { entered.countDown(); await(release); return item; }, receipts -> {
                        assertEquals(List.of(1, 2), receipts);
                        acknowledging.countDown(); await(releaseAcknowledgement);
                    }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var other = CompletableFuture.runAsync(() -> executor.deliverClaimedBatch(List.of(3), deadline,
                        items -> { otherClaimed.incrementAndGet(); return items; }, item -> item, items -> { }));
                release.countDown();
                assertTrue(acknowledging.await(2, TimeUnit.SECONDS));
                assertEquals(0, otherClaimed.get());
                assertFalse(first.isDone());
                releaseAcknowledgement.countDown();
                first.get(2, TimeUnit.SECONDS); other.get(2, TimeUnit.SECONDS);
                assertEquals(1, otherClaimed.get());
            } finally { release.countDown(); releaseAcknowledgement.countDown(); }
        }
    }

    @Test void failedBatchJoinsItsSiblingAndReleasesCapacityWithoutAcknowledging() throws Exception {
        try (var executor = new OutboxDeliveryExecutor("batch-failed-", 2)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var acknowledgements = new AtomicInteger();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            var drain = CompletableFuture.runAsync(() -> executor.deliverClaimedBatch(List.of(1, 2), deadline,
                    List::copyOf, item -> {
                        if (item == 1) throw new IllegalStateException("transport aborted");
                        entered.countDown(); await(release); return item;
                    }, items -> acknowledgements.incrementAndGet()));
            try { assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(drain.isDone()); }
            finally { release.countDown(); }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> drain.get(2, TimeUnit.SECONDS));
            assertEquals(0, acknowledgements.get());
            assertTrue(executor.deliverClaimedBatch(List.of(3, 4), deadline, List::copyOf,
                    item -> item, items -> acknowledgements.incrementAndGet()));
            assertEquals(1, acknowledgements.get());
        }
    }

    @Test void batchDeadlineAndClosedExecutorNeverConsumeAnUnadmittedClaim() {
        var executor = new OutboxDeliveryExecutor("batch-expired-", 2);
        var claims = new AtomicInteger();
        assertFalse(executor.deliverClaimedBatch(List.of(1, 2), System.nanoTime() - 1,
                items -> { claims.incrementAndGet(); return items; }, item -> item, items -> { }));
        executor.close();
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> executor.deliverClaimedBatch(
                List.of(1, 2), System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                items -> { claims.incrementAndGet(); return items; }, item -> item, items -> { }));
        assertEquals(0, claims.get());
    }

    @Test void admittedCohortFinishesAfterDeadlineButASecondCohortCannotClaim() throws Exception {
        try (var executor = new OutboxDeliveryExecutor("batch-admitted-", 2)) {
            var claiming = new CountDownLatch(1); var releaseClaim = new CountDownLatch(1);
            var sent = new AtomicInteger(); var receipts = new AtomicInteger(); var lateClaims = new AtomicInteger();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            var drain = CompletableFuture.runAsync(() -> executor.deliverClaimedBatch(List.of(1, 2), deadline,
                    items -> { claiming.countDown(); await(releaseClaim); return items; },
                    item -> { sent.incrementAndGet(); return item; }, items -> receipts.addAndGet(items.size())));
            try {
                assertTrue(claiming.await(1, TimeUnit.SECONDS));
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            } finally { releaseClaim.countDown(); }
            drain.get(2, TimeUnit.SECONDS);
            assertEquals(2, sent.get()); assertEquals(2, receipts.get());
            assertFalse(executor.deliverClaimedBatch(List.of(3), deadline,
                    items -> { lateClaims.incrementAndGet(); return items; }, item -> item, items -> { }));
            assertEquals(0, lateClaims.get());
        }
    }

    @Test void shutdownDuringClaimLeavesReceiptsUnacknowledgedForRecovery() {
        var executor = new OutboxDeliveryExecutor("batch-shutdown-", 2);
        var sent = new AtomicInteger(); var acknowledged = new AtomicInteger();
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> executor.deliverClaimedBatch(
                List.of(1, 2), System.nanoTime() + Duration.ofSeconds(2).toNanos(),
                items -> { executor.close(); return items; },
                item -> { sent.incrementAndGet(); return item; }, items -> acknowledged.incrementAndGet()));
        assertEquals(0, sent.get()); assertEquals(0, acknowledged.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("batch coordination timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
