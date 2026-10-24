package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Repairs old terminal waits without signals, action retries or changes to completed input. */
@Component
public class SoarTerminalWorkWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarTerminalWorkWorker.class);
    private final SoarRunRepository runs;
    private final SoarManualTaskRepository tasks;
    private final SoarApprovalRepository approvals;
    private final SoarNodeRunRepository nodes;
    private final TransactionTemplate transaction;

    public SoarTerminalWorkWorker(SoarRunRepository runs, SoarManualTaskRepository tasks,
                                  SoarApprovalRepository approvals, SoarNodeRunRepository nodes,
                                  PlatformTransactionManager transactions) {
        this.runs = runs;
        this.tasks = tasks;
        this.approvals = approvals;
        this.nodes = nodes;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(5);
    }

    @Scheduled(fixedDelayString = "${socp.soar.terminal-work-poll-ms:30000}",
            initialDelayString = "${socp.soar.terminal-work-initial-delay-ms:5000}")
    @TenantSystemJob
    public void tick() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (var candidate : runs.findTerminalHumanWorkCandidates(SoarPendingWork.TERMINAL, PageRequest.of(0, 100))) {
            if (System.nanoTime() >= deadline) break;
            try {
                TenantContext.runWith(candidate.getTenantId(), () -> transaction.executeWithoutResult(ignored ->
                        runs.findByTenantIdAndIdForUpdateSkipLocked(candidate.getTenantId(), candidate.getId())
                                .ifPresent(run -> SoarPendingWork.closeForTerminalRun(run, Instant.now(), tasks, approvals, nodes))));
            } catch (RuntimeException failure) {
                log.warn("Unable to reconcile terminal human work for SOAR run {}", candidate.getId());
            }
        }
    }
}
