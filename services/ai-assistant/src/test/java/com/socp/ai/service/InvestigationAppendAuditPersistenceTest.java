package com.socp.ai.service;

import com.socp.ai.api.controller.AiController;
import com.socp.ai.api.request.AppendInvestigationRequest;
import com.socp.ai.config.InvestigationProperties;
import com.socp.ai.infrastructure.llm.LlmChatClient;
import com.socp.ai.persistence.entity.InvestigationEntity;
import com.socp.ai.persistence.repository.InvestigationRepository;
import com.socp.platform.audit.aspect.AuditAspect;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.audit.spi.TransactionalAuditSink;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Spring-proxy proof that a controller audit rollback cannot erase a remotely used append target. */
@DataJpaTest(showSql = false)
@Import(InvestigationAppendAuditPersistenceTest.ProxyConfiguration.class)
class InvestigationAppendAuditPersistenceTest {

    @Autowired InvestigationRepository repository;
    @Autowired AiController controller;
    @Autowired IncidentClient incidents;
    @Autowired FailingAuditSink controllerAudit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        TenantContext.set("tenant-a");
        jdbc.update("delete from t_ai_investigation");
        reset(incidents);
        controllerAudit.failSuccess = false;
        controllerAudit.failAppendTool = false;
    }

    @AfterEach
    void clear() {
        controllerAudit.failSuccess = false;
        controllerAudit.failAppendTool = false;
        TenantContext.clear();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void remoteSuccessKeepsItsCommittedTargetWhenOuterAuditRollsBack() {
        InvestigationEntity entity = new InvestigationEntity();
        entity.setId("INV-1");
        entity.setTenantId("tenant-a");
        entity.setAlertId("AL-1");
        entity.setRevision(1);
        entity.setStatus("COMPLETED");
        entity.setResultJson("{\"alertId\":\"AL-1\",\"alert\":{\"id\":\"AL-1\"},\"citations\":[]}");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        repository.saveAndFlush(entity);
        given(incidents.addNote(eq("CASE-A"), anyString(), anyString(), eq("INV-1")))
                .willReturn(new ServiceCall(SocpService.INCIDENT, "http://incident", true, 200,
                        "{\"data\":{\"case\":{\"id\":\"CASE-A\"}}}",
                        null, 1, false, 1));
        controllerAudit.failSuccess = true;

        assertThatThrownBy(() -> controller.appendToIncident(
                "INV-1", new AppendInvestigationRequest("CASE-A")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit sink unavailable");

        InvestigationEntity stored = repository.findByIdAndTenantId("INV-1", "tenant-a").orElseThrow();
        assertThat(stored.getIncidentId()).isEqualTo("CASE-A");
        assertThat(stored.getAppendedAt()).isNotNull();

        controllerAudit.failSuccess = false;
        var retry = controller.appendToIncident(
                "INV-1", new AppendInvestigationRequest("CASE-B")).data();
        assertThat(retry.get("incidentId")).isEqualTo("CASE-A");
        assertThat(retry.get("duplicate")).isEqualTo(true);
        verify(incidents, times(1)).addNote(eq("CASE-A"), anyString(), anyString(), eq("INV-1"));
        verify(incidents, never()).addNote(eq("CASE-B"), anyString(), anyString(), anyString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void remoteSuccessKeepsItsBoundTargetWhenServiceAuditFailsBeforeCompletion() {
        InvestigationEntity entity = investigation("INV-TOOL-AUDIT", "AL-TOOL-AUDIT");
        repository.saveAndFlush(entity);
        given(incidents.addNote(eq("CASE-A"), anyString(), anyString(), eq("INV-TOOL-AUDIT")))
                .willReturn(successfulNote("CASE-A"));
        controllerAudit.failAppendTool = true;

        assertThatThrownBy(() -> controller.appendToIncident(
                "INV-TOOL-AUDIT", new AppendInvestigationRequest("CASE-A")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool audit unavailable");

        InvestigationEntity bound = repository.findByIdAndTenantId(
                "INV-TOOL-AUDIT", "tenant-a").orElseThrow();
        assertThat(bound.getIncidentId()).isEqualTo("CASE-A");
        assertThat(bound.getAppendedAt()).isNull();

        controllerAudit.failAppendTool = false;
        assertThatThrownBy(() -> controller.appendToIncident(
                "INV-TOOL-AUDIT", new AppendInvestigationRequest("CASE-B")))
                .hasMessageContaining("CASE-A");
        var retry = controller.appendToIncident(
                "INV-TOOL-AUDIT", new AppendInvestigationRequest("CASE-A")).data();
        assertThat(retry.get("incidentId")).isEqualTo("CASE-A");
        assertThat(repository.findByIdAndTenantId(
                "INV-TOOL-AUDIT", "tenant-a").orElseThrow().getAppendedAt()).isNotNull();
        verify(incidents, times(2)).addNote(
                eq("CASE-A"), anyString(), anyString(), eq("INV-TOOL-AUDIT"));
        verify(incidents, never()).addNote(eq("CASE-B"), anyString(), anyString(), anyString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentControllerRequestsCommitOneTargetWithoutHoldingAnOuterAuditTransaction()
            throws Exception {
        repository.saveAndFlush(investigation("INV-RACE", "AL-RACE"));
        given(incidents.addNote(anyString(), anyString(), anyString(), eq("INV-RACE")))
                .willAnswer(invocation -> successfulNote(invocation.getArgument(0)));

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> appendAfter(start, "CASE-A"));
            var second = pool.submit(() -> appendAfter(start, "CASE-B"));
            start.countDown();
            Object firstResult = first.get();
            Object secondResult = second.get();

            long successes = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(Map.class::isInstance).count();
            long conflicts = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .filter(failure -> failure.getCode() == 409).count();
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();

            InvestigationEntity stored = repository.findByIdAndTenantId(
                    "INV-RACE", "tenant-a").orElseThrow();
            assertThat(stored.getIncidentId()).isIn("CASE-A", "CASE-B");
            assertThat(stored.getAppendedAt()).isNotNull();
            Map<?, ?> receipt = (Map<?, ?>) java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(Map.class::isInstance).findFirst().orElseThrow();
            assertThat(receipt.get("incidentId")).isEqualTo(stored.getIncidentId());
            verify(incidents, times(1)).addNote(
                    eq(stored.getIncidentId()), anyString(), anyString(), eq("INV-RACE"));
        }
    }

    private Object appendAfter(CountDownLatch start, String incidentId) throws Exception {
        start.await();
        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            try {
                return controller.appendToIncident(
                        "INV-RACE", new AppendInvestigationRequest(incidentId)).data();
            } catch (ApiException failure) {
                return failure;
            }
        }
    }

    private static InvestigationEntity investigation(String id, String alertId) {
        InvestigationEntity entity = new InvestigationEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setAlertId(alertId);
        entity.setRevision(1);
        entity.setStatus("COMPLETED");
        entity.setResultJson("{\"alertId\":\"" + alertId
                + "\",\"alert\":{\"id\":\"" + alertId + "\"},\"citations\":[]}");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private static ServiceCall successfulNote(String incidentId) {
        return new ServiceCall(SocpService.INCIDENT, "http://incident", true, 200,
                "{\"data\":{\"case\":{\"id\":\"" + incidentId + "\"}}}",
                null, 1, false, 1);
    }

    static final class FailingAuditSink implements TransactionalAuditSink {
        volatile boolean failSuccess;
        volatile boolean failAppendTool;

        @Override
        public void publish(AuditRecord record) {
            if (failAppendTool && "AI_TOOL_CALL".equals(record.action())
                    && record.result().contains("incident.append-summary")) {
                throw new IllegalStateException("tool audit unavailable");
            }
            if (failSuccess && "SUCCESS".equals(record.result())) {
                throw new IllegalStateException("audit sink unavailable");
            }
        }
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class ProxyConfiguration {
        @Bean FailingAuditSink auditSink() {
            return new FailingAuditSink();
        }

        @Bean AuditAspect auditAspect(AuditSink sink,
                                      ObjectProvider<PlatformTransactionManager> managers) {
            return new AuditAspect(sink, managers);
        }

        @Bean IncidentClient incidentClient() {
            return org.mockito.Mockito.mock(IncidentClient.class);
        }

        @Bean InvestigationAgentService investigationAgentService(
                InvestigationRepository repository, IncidentClient incidents,
                FailingAuditSink auditSink) {
            InvestigationProperties properties = new InvestigationProperties();
            properties.setTimeoutMs(10_000);
            return new InvestigationAgentService(repository,
                    org.mockito.Mockito.mock(AlertClient.class),
                    org.mockito.Mockito.mock(SearchClient.class), incidents,
                    org.mockito.Mockito.mock(ThreatClient.class),
                    org.mockito.Mockito.mock(LlmChatClient.class), auditSink, properties);
        }

        @Bean AiController aiController(InvestigationAgentService investigations) {
            return new AiController(org.mockito.Mockito.mock(AiAssistantService.class), investigations);
        }
    }
}
