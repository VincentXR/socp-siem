package com.socp.notify.web.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class NotificationExecutorTest {
    @Test @Timeout(10)
    void capacityHasNoQueueAndCallerTimeoutDoesNotAdmitMoreIo() throws Exception {
        var executor = new NotificationExecutor();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(32);
        var futures = new ArrayList<CompletableFuture<Integer>>();
        try {
            for (int i = 0; i < 32; i++) futures.add(executor.submit(() -> {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture release timed out"); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return 1;
            }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> futures.getFirst().get(1, TimeUnit.MILLISECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> 2));
            release.countDown();
            for (var future : futures) assertEquals(1, future.get(3, TimeUnit.SECONDS));
            assertEquals(2, executor.submit(() -> 2).get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.close(); }
        assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> 2));
    }
}
