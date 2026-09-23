package com.socp.notify.web.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** No waiting queue. A timed-out caller does not free capacity while its I/O still runs. */
@Component
public class NotificationExecutor {
    private final Semaphore permits = new Semaphore(32);
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("notify-delivery-", 0).factory());

    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        if (!permits.tryAcquire()) throw new RejectedExecutionException("Notification capacity exhausted");
        try {
            var result = new CompletableFuture<T>();
            executor.execute(() -> {
                try {
                    if (!result.isCancelled()) result.complete(operation.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally { permits.release(); }
            });
            return result;
        } catch (RuntimeException rejected) {
            permits.release();
            throw rejected;
        }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
