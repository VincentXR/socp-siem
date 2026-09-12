package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retention janitor for the run evidence family.
 *
 * <p>Defaults follow design 11.2: terminal Run/NodeRun/ActionAttempt rows are
 * purged 180 days after their last update, and the run-event timeline (the
 * operator-facing audit trace) is retained at least 365 days.  Both defaults
 * can be raised per deployment ({@code socp.soar.retention.run-days} and
 * {@code ...event-days}); a value of 0 disables that pass.  Only terminal runs
 * are ever removed so an in-flight workflow can never lose its projections.
 *
 * <p>This is a system-scope job ({@link TenantSystemJob}); all short-lived
 * child rows are deleted before their owning run.  Timeline and artifact
 * handles can have a longer evidence lifetime, so a parent run is retained
 * until those rows have been removed by their own retention passes.</p>
 */
@Component
public class SoarRunRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarRunRetentionWorker.class);
    private static final int RUN_BATCH = 100;
    private static final int EVENT_BATCH = 500;
    private static final int MAX_PASSES = 20;
    private static final int CHUNK = 500;

    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "TIMED_OUT",
            "ACTION_UNKNOWN", "CANCELLED", "SUPPRESSED", "DEAD");

    private final SoarRunRepository runs;
    private final SoarNodeRunRepository nodes;
    private final SoarActionAttemptRepository attempts;
    private final SoarRunEventRepository events;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarApprovalRepository approvals;
    private final SoarApprovalDecisionRepository approvalDecisions;
    private final SoarManualTaskRepository manualTasks;
    private final SoarSignalOutboxRepository signals;
    private final SoarArtifactRepository artifacts;

    @Value("${socp.soar.retention.run-days:180}")
    private long runDays;

    @Value("${socp.soar.retention.event-days:365}")
    private long eventDays;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarRunRetentionWorker(SoarRunRepository runs, SoarNodeRunRepository nodes,
                                  SoarActionAttemptRepository attempts, SoarRunEventRepository events,
                                  SoarDispatchOutboxRepository dispatches, SoarApprovalRepository approvals,
                                  SoarApprovalDecisionRepository approvalDecisions,
                                  SoarManualTaskRepository manualTasks,
                                  SoarSignalOutboxRepository signals, SoarArtifactRepository artifacts) {
        this.runs = runs;
        this.nodes = nodes;
        this.attempts = attempts;
        this.events = events;
        this.dispatches = dispatches;
        this.approvals = approvals;
        this.approvalDecisions = approvalDecisions;
        this.manualTasks = manualTasks;
        this.signals = signals;
        this.artifacts = artifacts;
    }

    /** Compatibility constructor for isolated retention tests. */
    SoarRunRetentionWorker(SoarRunRepository runs, SoarNodeRunRepository nodes,
                           SoarActionAttemptRepository attempts, SoarRunEventRepository events) {
        this(runs, nodes, attempts, events, null, null, null, null, null, null);
    }

    @Scheduled(fixedDelayString = "${socp.soar.run-retention-poll-ms:3600000}",
            initialDelayString = "${socp.soar.run-retention-initial-delay-ms:600000}")
    @TenantSystemJob
    @Transactional
    public void tick() {
        int purgedRuns = 0;
        if (runDays > 0) {
            Instant runCutoff = Instant.now().minus(Duration.ofDays(runDays));
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                List<SoarRunEntity> batch = hasFullRetentionRepositories()
                        ? runs.findTopPurgeableByStatusInAndUpdatedAtBefore(
                                TERMINAL, runCutoff, PageRequest.of(0, RUN_BATCH))
                        : runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(TERMINAL, runCutoff);
                if (batch.isEmpty()) break;
                int purged = purgeRunFamily(batch);
                purgedRuns += purged;
                // A defensive evidence scan can still veto a row if a
                // concurrent writer inserted a retained child after the SQL
                // candidate query. Avoid spinning on that page; the next
                // scheduled tick will retry it after the evidence pass.
                if (purged == 0 && hasFullRetentionRepositories()) break;
                if (batch.size() < RUN_BATCH) break;
            }
        }
        int purgedEvents = 0;
        if (eventDays > 0) {
            Instant eventCutoff = Instant.now().minus(Duration.ofDays(eventDays));
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                List<String> ids = events.findIdsCreatedBefore(eventCutoff, PageRequest.of(0, EVENT_BATCH));
                if (ids == null || ids.isEmpty()) break;
                events.deleteByIds(ids);
                purgedEvents += ids.size();
                if (ids.size() < EVENT_BATCH) break;
            }
        }
        if (purgedRuns > 0 || purgedEvents > 0) {
            log.info("SOAR retention purged {} run families and {} run events", purgedRuns, purgedEvents);
        }
    }

    int purgeRunFamily(List<SoarRunEntity> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        List<String> runIds = batch.stream().map(SoarRunEntity::getId).toList();

        // Older isolated tests construct the worker with only the original
        // four repositories. The Spring-managed path always has the complete
        // dependency set and uses the explicit ownership-aware ordering below.
        if (!hasFullRetentionRepositories()) {
            List<String> nodeIds = nodes.findIdsByRunIdIn(runIds);
            for (List<String> chunk : chunk(nodeIds)) {
                attempts.deleteByNodeRunIdIn(chunk);
            }
            nodes.deleteByRunIdIn(runIds);
            return runs.deleteByIds(runIds);
        }

        // Timeline and artifact retention have longer/independent lifetimes
        // than a run. Keep the parent row until those handles are gone; this
        // both preserves the documented evidence window and satisfies 2's
        // non-cascading foreign keys.
        Set<String> blocked = new HashSet<>();
        addAll(blocked, events.findRunIdsByRunIdIn(runIds));
        addAll(blocked, artifacts.findRunIdsByRunIdIn(runIds));
        List<String> purgeable = runIds.stream().filter(id -> !blocked.contains(id)).toList();
        if (purgeable.isEmpty()) return 0;

        List<String> nodeIds = nodes.findIdsByRunIdIn(purgeable);
        for (List<String> chunk : chunk(nodeIds)) {
            attempts.deleteByNodeRunIdIn(chunk);
        }

        List<String> approvalIds = approvals.findIdsByRunIdIn(purgeable);
        if (approvalIds != null && !approvalIds.isEmpty()) {
            approvalDecisions.deleteByApprovalIdIn(approvalIds);
        }
        dispatches.deleteByRunIdIn(purgeable);
        approvals.deleteByRunIdIn(purgeable);
        manualTasks.deleteByRunIdIn(purgeable);
        signals.deleteByRunIdIn(purgeable);
        nodes.deleteByRunIdIn(purgeable);
        return runs.deleteByIds(purgeable);
    }

    private boolean hasFullRetentionRepositories() {
        return dispatches != null && approvals != null && approvalDecisions != null
                && manualTasks != null && signals != null && artifacts != null;
    }

    private static void addAll(Set<String> target, Collection<String> values) {
        if (values != null) target.addAll(values);
    }

    private static List<List<String>> chunk(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        if (values == null || values.isEmpty()) return chunks;
        for (int index = 0; index < values.size(); index += CHUNK) {
            chunks.add(values.subList(index, Math.min(values.size(), index + CHUNK)));
        }
        return chunks;
    }
}
