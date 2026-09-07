package com.socp.soar.web.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle-owned resources for the run-event SSE projection.
 *
 * <p>The stream currently projects durable events by polling the run-event
 * table.  Keeping the scheduler as a Spring bean makes shutdown deterministic,
 * allows deployments to size it explicitly, and prevents a static executor
 * from surviving application context refreshes.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SoarSseConfiguration {

    @Bean(name = "soarSseScheduler", destroyMethod = "shutdown")
    @Qualifier("soarSseScheduler")
    public ScheduledExecutorService soarSseScheduler(SoarRuntimeProperties properties) {
        int threads = Math.max(1, Math.min(32, properties.getSseSchedulerThreads()));
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads,
                namedThreadFactory("soar-v2-sse"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
