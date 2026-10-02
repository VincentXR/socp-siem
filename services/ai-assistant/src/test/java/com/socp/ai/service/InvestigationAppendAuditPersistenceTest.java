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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        CountDownLatch noteEntered = new CountDownLatch(1);
        CountDownLatch releaseNote = new CountDownLatch(1);
        given(incidents.addNote(anyString(), anyString(), anyString(), eq("INV-RACE")))
                .willAnswer(invocation -> {
                    noteEntered.countDown();
                    assertThat(releaseNote.await(60, TimeUnit.SECONDS))
                            .as("the test must release the in-flight remote append")
                            .isTrue();
                    return successfulNote(invocation.getArgument(0));
                });

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> append("CASE-A"));
            Future<Object> second = null;
            try {
                assertThat(noteEntered.await(30, TimeUnit.SECONDS))
                        .as("CASE-A must reach its remote call with a committed append claim")
                        .isTrue();
                // Hold the winner open: a request after completion is a valid duplicate,
                // so releasing two threads together alone does not prove contention.
                second = pool.submit(() -> append("CASE-B"));
                Object secondResult = second.get(30, TimeUnit.SECONDS);
                assertThat(secondResult).isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(409));

                releaseNote.countDown();
                Object firstResult = first.get(30, TimeUnit.SECONDS);
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
                assertThat(stored.getIncidentId()).isEqualTo("CASE-A");
                assertThat(stored.getAppendedAt()).isNotNull();
                Map<?, ?> receipt = (Map<?, ?>) firstResult;
                assertThat(receipt.get("incidentId")).isEqualTo(stored.getIncidentId());
                verify(incidents, times(1)).addNote(
                        eq(stored.getIncidentId()), anyString(), anyString(), eq("INV-RACE"));
                verify(incidents, times(1)).addNote(
                        anyString(), anyString(), anyString(), eq("INV-RACE"));
            } finally {
                releaseNote.countDown();
                first.cancel(true);
                if (second != null) second.cancel(true);
            }
        }
    }

    private Object append(String incidentId) {
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
