package com.socp.soar.web.config;

import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Retry failed initial worker registration without replacing the durable dispatcher. */
@Component
public class TemporalWorkerRecovery {
    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerRecovery.class);
    private final WorkerFactory factory;
    private final TemporalProperties properties;
    // Registration can block on a namespace RPC. Do not hold the shared
    // maintenance scheduler, which also dispatches signals and expires gates.
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "soar-temporal-worker-recovery");
        thread.setDaemon(true);
        return thread;
    });

    public TemporalWorkerRecovery(WorkerFactory factory, TemporalProperties properties) {
        this.factory = factory;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (properties.isEnabled()) retries.scheduleWithFixedDelay(this::recover, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() { retries.shutdownNow(); }

    public void recover() {
        if (retries.isShutdown() || !properties.isEnabled() || factory.isStarted() || factory.isShutdown()) return;
        try {
            factory.start();
            log.info("Temporal Worker recovered after an initial startup failure");
        } catch (Exception unavailable) {
            log.warn("Temporal Worker is not ready; startup will be retried: {}", unavailable.getMessage());
        }
    }
}
