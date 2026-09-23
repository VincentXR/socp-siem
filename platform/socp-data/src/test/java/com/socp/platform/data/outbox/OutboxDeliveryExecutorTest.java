package com.socp.platform.data.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
