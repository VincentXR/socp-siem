package com.socp.platform.data.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Limits local delivery admission; database claim tokens still own distributed correctness. */
public final class OutboxDeliveryExecutor implements AutoCloseable {
    private final ExecutorService executor;
    private final Semaphore permits;

    public OutboxDeliveryExecutor(String threadPrefix, int concurrency) {
        permits = new Semaphore(Math.max(1, Math.min(32, concurrency)));
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(threadPrefix, 0).factory());
    }

    /**
     * Acquire capacity before invoking the callback that claims a row. No new
     * callbacks start after the admission deadline; admitted I/O is allowed to
     * finish within its transport timeout, and all admitted work is joined.
     */
    public <T> void deliver(List<T> candidates, long deadlineNanos, Consumer<T> delivery) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        try {
            for (T candidate : candidates) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    if (!permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) break;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    pending.add(CompletableFuture.runAsync(() -> {
                        try {
                            if (System.nanoTime() < deadlineNanos) delivery.accept(candidate);
                        } finally {
                            permits.release();
                        }
                    }, executor));
                } catch (RuntimeException rejected) {
                    permits.release();
                    throw rejected;
                }
            }
        } finally {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        }
    }

    @Override
    public void close() { executor.shutdownNow(); }
}
