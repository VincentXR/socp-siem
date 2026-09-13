package com.socp.soar.web.api.controller;

import com.socp.soar.web.service.SoarRunQueryService;
import com.socp.soar.web.service.SoarService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-side compatibility bridge for the SOAR controller.
 *
 * <p>The V2 query service is optional in sliced tests and in older
 * deployments. Keeping the fallback in one object prevents every controller
 * route from carrying a second service-selection branch while preserving the
 * existing HTTP contract.</p>
 */
final class SoarControllerReadSupport {
    private final SoarService service;
    private SoarRunQueryService runQueries;

    SoarControllerReadSupport(SoarService service) {
        this.service = service;
    }

    void setRunQueries(SoarRunQueryService runQueries) {
        this.runQueries = runQueries;
    }

    Page<Map<String, Object>> runs(Pageable pageable, String status,
                                   String playbookVersionId, String triggerType,
                                   String requestedBy, Instant createdFrom, Instant createdTo) {
        return runQueries == null
                ? service.listRuns(pageable, status, playbookVersionId, triggerType, requestedBy,
                createdFrom, createdTo)
                : runQueries.listRuns(pageable, status, playbookVersionId, triggerType, requestedBy,
                createdFrom, createdTo);
    }

    Map<String, Object> run(String id) {
        return runQueries == null ? service.getRun(id) : runQueries.getRun(id);
    }

    List<Map<String, Object>> nodes(String runId) {
        return runQueries == null ? service.listNodes(runId) : runQueries.listNodes(runId);
    }

    Page<Map<String, Object>> nodes(String runId, Pageable pageable) {
        return runQueries == null ? service.listNodes(runId, pageable) : runQueries.listNodes(runId, pageable);
    }

    List<Map<String, Object>> artifacts(String runId) {
        return runQueries == null ? service.listArtifacts(runId) : runQueries.listArtifacts(runId);
    }

    Page<Map<String, Object>> artifacts(String runId, Pageable pageable) {
        return runQueries == null ? service.listArtifacts(runId, pageable)
                : runQueries.listArtifacts(runId, pageable);
    }

    Map<String, Object> artifact(String id) {
        return runQueries == null ? service.getArtifact(id) : runQueries.getArtifact(id);
    }

    String artifactContent(String id) {
        return runQueries == null ? service.getArtifactContent(id) : runQueries.getArtifactContent(id);
    }

    Page<Map<String, Object>> nodeAttempts(String nodeRunId, Pageable pageable) {
        return runQueries == null ? service.listNodeAttempts(nodeRunId, pageable)
                : runQueries.listNodeAttempts(nodeRunId, pageable);
    }

    List<Map<String, Object>> events(String runId) {
        return runQueries == null ? service.listEvents(runId) : runQueries.listEvents(runId);
    }

    Page<Map<String, Object>> events(String runId, long afterSequence, Pageable pageable) {
        return runQueries == null ? service.listEvents(runId, afterSequence, pageable)
                : runQueries.listEvents(runId, afterSequence, pageable);
    }

    List<Map<String, Object>> manualTasks(boolean pendingOnly) {
        return runQueries == null ? service.listManualTasks(pendingOnly)
                : runQueries.listManualTasks(pendingOnly);
    }

    Page<Map<String, Object>> manualTasks(boolean pendingOnly, Pageable pageable) {
        return runQueries == null ? service.listManualTasks(pendingOnly, pageable)
                : runQueries.listManualTasks(pendingOnly, pageable);
    }

    Map<String, Object> stats() {
        return runQueries == null ? service.stats() : runQueries.stats();
    }

    List<Map<String, Object>> deadDispatches() {
        return runQueries == null ? service.deadDispatches() : runQueries.deadDispatches();
    }

    List<Map<String, Object>> approvals() {
        return runQueries == null ? service.listApprovals() : runQueries.listApprovals();
    }

    Page<Map<String, Object>> approvals(Pageable pageable) {
        return runQueries == null ? service.listApprovals(pageable) : runQueries.listApprovals(pageable);
    }
}
