package com.socp.soar.web.service;

import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** Exercises the real SoarV2Service.healthBacklog() body against mocked counters. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoarV2ServiceHealthBacklogTest {

    @Mock
    private SoarPlaybookRepository playbooks;
    @Mock
    private PlaybookVersionRepository versions;
    @Mock
    private SoarRunRepository runs;
    @Mock
    private SoarDispatchOutboxRepository dispatches;
    @Mock
    private SoarNodeRunRepository nodes;
    @Mock
    private SoarRunEventRepository events;
    @Mock
    private SoarApprovalRepository approvals;
    @Mock
    private SoarDefinitionValidator validator;
    @Mock
    private TemporalExecutor temporal;
    @Mock
    private SoarActionAttemptRepository attempts;
    @Mock
    private SoarManualTaskRepository manualTasks;
    @Mock
    private SoarSignalOutboxRepository signals;
    @Mock
    private SoarConnectorRepository connectors;
    @Mock
    private SoarConnectorRegistry connectorRegistry;
    @Mock
    private SoarArtifactRepository artifacts;

    private SoarV2Service service() {
        SoarV2Service service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events,
                approvals, validator, new ObjectMapper(), temporal, attempts, manualTasks, signals,
                connectors, connectorRegistry);
        service.setArtifacts(artifacts);
        return service;
    }

    @Test
    void healthBacklogReportsBothOutboxes() {
        given(dispatches.countByStatus("PENDING")).willReturn(3L);
        given(dispatches.countByStatus("DEAD")).willReturn(1L);
        given(signals.countByStatus("PENDING")).willReturn(5L);
        given(signals.countByStatus("DEAD")).willReturn(2L);

        Map<String, Object> backlog = service().healthBacklog();

        assertThat(backlog)
                .containsEntry("dispatchBacklog", 3L)
                .containsEntry("dispatchDead", 1L)
                .containsEntry("signalBacklog", 5L)
                .containsEntry("signalDead", 2L);
    }

    @Test
    void healthBacklogSkipsNullSignalStore() {
        given(dispatches.countByStatus("PENDING")).willReturn(0L);
        given(dispatches.countByStatus("DEAD")).willReturn(0L);

        SoarV2Service service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events,
                approvals, validator, new ObjectMapper(), temporal, attempts, manualTasks, null,
                connectors, connectorRegistry);
        Map<String, Object> backlog = service.healthBacklog();

        assertThat(backlog)
                .containsEntry("dispatchBacklog", 0L)
                .containsEntry("dispatchDead", 0L)
                .doesNotContainKeys("signalBacklog", "signalDead");
    }
}
