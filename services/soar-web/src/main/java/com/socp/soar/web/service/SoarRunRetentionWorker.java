package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
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
import java.util.List;
import java.util.Set;

/**
 * Retention janitor for the run evidence family.
 *
 * <p>Defaults follow design 11.2: terminal Run/NodeRun/ActionAttempt rows are
 * purged 180 days after their last update, and the run-event timeline (the
 * operator-facing audit trace) is retained at least 365 days.  Both defaults
 * can be raised per deployment ({@code socp.soar.v2.retention.run-days} and
 * {@code ...event-days}); a value of 0 disables that pass.  Only terminal runs
 * are ever removed so an in-flight workflow can never lose its projections.
 *
 * <p>This is a system-scope job ({@link TenantSystemJob}); child rows are
 * deleted before their owning run so no orphan projections survive.</p>
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

    @Value("${socp.soar.v2.retention.run-days:180}")
    private long runDays;

    @Value("${socp.soar.v2.retention.event-days:365}")
    private long eventDays;

    public SoarRunRetentionWorker(SoarRunRepository runs, SoarNodeRunRepository nodes,
                                  SoarActionAttemptRepository attempts, SoarRunEventRepository events) {
        this.runs = runs;
        this.nodes = nodes;
        this.attempts = attempts;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.run-retention-poll-ms:3600000}",
            initialDelayString = "${socp.soar.v2.run-retention-initial-delay-ms:600000}")
    @TenantSystemJob
    public void tick() {
        int purgedRuns = 0;
        if (runDays > 0) {
            Instant runCutoff = Instant.now().minus(Duration.ofDays(runDays));
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                List<SoarRunEntity> batch = runs
                        .findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(TERMINAL, runCutoff);
                if (batch.isEmpty()) break;
                purgeRunFamily(batch);
                purgedRuns += batch.size();
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

    @Transactional
    void purgeRunFamily(List<SoarRunEntity> batch) {
        List<String> runIds = batch.stream().map(SoarRunEntity::getId).toList();
        List<String> nodeIds = nodes.findIdsByRunIdIn(runIds);
        for (List<String> chunk : chunk(nodeIds)) {
            attempts.deleteByNodeRunIdIn(chunk);
        }
        nodes.deleteByRunIdIn(runIds);
        runs.deleteByIds(runIds);
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
