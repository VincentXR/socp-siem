package com.socp.ai.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.ai.persistence.repository.InvestigationRepository;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Durable queue with bounded execution; unstarted work survives process termination. */
@Service
public class AsyncInvestigationJobService {
    private static final Logger log = LoggerFactory.getLogger(AsyncInvestigationJobService.class);
    private final InvestigationAgentService agent;
    private final InvestigationRepository repository;
    private final ThreadPoolExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AsyncInvestigationJobService(InvestigationAgentService agent,
                                       InvestigationRepository repository,
                                       @Value("${socp.ai.investigation.async-workers:2}") int workers) {
        this.agent = agent;
        this.repository = repository;
        int boundedWorkers = Math.max(1, Math.min(16, workers));
        this.executor = new ThreadPoolExecutor(boundedWorkers, boundedWorkers, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().name("investigation-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public Map<String, Object> submit(String alertId) {
        var receipt = agent.enqueue(alertId);
        return Map.of("jobId", receipt.getId(), "status", "ACCEPTED", "alertId", receipt.getAlertId(),
                "poll", "/api/v1/ai/investigations/" + receipt.getId());
    }

    @Scheduled(fixedDelayString = "${socp.ai.investigation.async-poll-ms:1000}",
            initialDelayString = "${socp.ai.investigation.async-poll-ms:1000}")
    @TenantSystemJob
    public void dispatch() {
        int capacity = executor.getMaximumPoolSize() - inFlight.size();
        if (capacity <= 0 || executor.isShutdown()) return;
        for (var receipt : repository.findRecoverable(Instant.now(), PageRequest.of(0, capacity))) {
            if (!inFlight.add(receipt.getId())) continue;
            try {
                executor.execute(() -> {
                    try {
                        TenantContext.runWith(receipt.getTenantId(), () -> agent.investigate(receipt.getAlertId()));
                    } catch (RuntimeException failure) {
                        log.warn("Investigation attempt failed id={} type={}", receipt.getId(),
                                failure.getClass().getSimpleName());
                    } finally {
                        inFlight.remove(receipt.getId());
                    }
                });
            } catch (RejectedExecutionException full) {
                inFlight.remove(receipt.getId());
                break;
            }
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
