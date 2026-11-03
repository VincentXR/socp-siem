package com.socp.platform.data.outbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Limits local delivery admission; database claim tokens still own distributed correctness. */
public final class OutboxDeliveryExecutor implements AutoCloseable {
    private final ExecutorService executor;
    private final Semaphore permits;
    private final int capacity;

    public OutboxDeliveryExecutor(String threadPrefix, int concurrency) {
        capacity = Math.max(1, Math.min(32, concurrency));
        permits = new Semaphore(capacity);
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(threadPrefix, 0).factory());
    }

    /**
     * Reserve a whole bounded cohort before its durable claim. Claim and receipt
     * callbacks each finish their transaction outside transport I/O. All claimed
     * work is joined before capacity is released, including on failure or shutdown.
     */
    public <T, C, R> boolean deliverClaimedBatch(List<T> candidates, long deadlineNanos,
            Function<List<T>, List<C>> claim, Function<C, R> delivery, Consumer<List<R>> acknowledge) {
        if (candidates.isEmpty()) return true;
        if (candidates.size() > capacity) throw new IllegalArgumentException("cohort exceeds delivery capacity");
        if (executor.isShutdown()) throw new java.util.concurrent.RejectedExecutionException("delivery executor closed");
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return false;
        try {
            if (!permits.tryAcquire(candidates.size(), remaining, TimeUnit.NANOSECONDS)) return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        List<CompletableFuture<R>> pending = new ArrayList<>();
        try {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) return false;
            if (executor.isShutdown()) throw new java.util.concurrent.RejectedExecutionException("delivery executor closed");
            List<C> claimed = List.copyOf(claim.apply(List.copyOf(candidates)));
            if (claimed.size() > candidates.size()) throw new IllegalStateException("claim exceeds reserved capacity");
            if (claimed.isEmpty()) return true;
            try {
                for (C item : claimed) pending.add(CompletableFuture.supplyAsync(() -> delivery.apply(item), executor));
            } finally {
                // A late admission/transport failure cannot abandon an already
                // running sibling. Unacknowledged claims retain durable recovery.
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
            }
            acknowledge.accept(pending.stream().map(CompletableFuture::join).toList());
            return true;
        } finally {
            permits.release(candidates.size());
        }
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

    /**
     * Delivers each key's candidates in encounter order while different keys may progress in
     * parallel. This is a local admission guarantee only; publishers must also fence claims in
     * their durable store so that another replica cannot overtake an older row for the same key.
     */
    public <T, K> void deliverKeyed(List<T> candidates, long deadlineNanos,
                                    Function<T, K> keySelector, Consumer<T> delivery) {
        Map<K, List<T>> groups = new LinkedHashMap<>();
        for (T candidate : candidates) {
            groups.computeIfAbsent(keySelector.apply(candidate), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        deliver(new ArrayList<>(groups.values()), deadlineNanos, group -> {
            for (T candidate : group) {
                if (System.nanoTime() >= deadlineNanos) break;
                delivery.accept(candidate);
            }
        });
    }

    @Override
    public void close() { executor.shutdownNow(); }
}
