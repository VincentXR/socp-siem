package com.socp.ai.service;

import com.socp.ai.config.InvestigationProperties;
import com.socp.ai.infrastructure.llm.LlmChatClient;
import com.socp.ai.persistence.entity.InvestigationEntity;
import com.socp.ai.persistence.repository.InvestigationRepository;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InvestigationAgentServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void buildsCitedTimelineAndNeverCallsSoarForAnInvestigation() {
        TenantContext.set("tenant-a");
        InvestigationRepository repository = mock(InvestigationRepository.class);
        AlertClient alerts = mock(AlertClient.class);
        SearchClient search = mock(SearchClient.class);
        IncidentClient incidents = mock(IncidentClient.class);
        ThreatClient threat = mock(ThreatClient.class);
        LlmChatClient llm = mock(LlmChatClient.class);
        AuditSink audit = mock(AuditSink.class);
        InvestigationProperties properties = properties();

        given(repository.findByTenantIdAndAlertId("tenant-a", "AL-1")).willReturn(Optional.empty());
        given(repository.claim(anyString(), anyString(), anyString(), any(), any())).willReturn(1);
        given(repository.complete(anyString(), anyString(), anyString(), anyString(), anyString(), any())).willReturn(1);
        given(alerts.getAlarm("AL-1")).willReturn(ok("{\"data\":{\"id\":\"AL-1\",\"ruleId\":\"AUTH-PRIVESC\",\"entity\":\"host-1\",\"severity\":\"HIGH\",\"message\":\"sudo\",\"occurredAt\":\"2026-08-20T10:00:00Z\"}}", SocpService.ALERT));
        given(alerts.evidence("AL-1")).willReturn(ok("{\"data\":{\"items\":[{\"eventId\":\"EV-1\",\"timestamp\":\"2026-08-20T09:59:00Z\",\"raw\":\"sudo -l\",\"src_ip\":\"10.1.2.3\"}]}}", SocpService.ALERT));
        given(search.search(anyString())).willReturn(ok("{\"events\":[{\"eventId\":\"EV-1\",\"timestamp\":\"2026-08-20T09:59:00Z\",\"msg\":\"sudo -l\"}]}", SocpService.SEARCH));
        given(incidents.list()).willReturn(ok("[]", SocpService.INCIDENT));
        given(threat.matchIocs(anyString())).willReturn(ok("{\"checked\":1,\"matched\":1,\"hits\":{\"10.1.2.3\":{\"type\":\"IP\"}}}", SocpService.THREAT));
        given(llm.isEnabled()).willReturn(false);

        InvestigationAgentService service = new InvestigationAgentService(
                repository, alerts, search, incidents, threat, llm, audit, properties);

        Map<String, Object> result = service.investigate("AL-1");

        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("recommendedSpl")).asString().contains("eventId=EV-1");
        assertThat((List<?>) result.get("timeline")).hasSize(3);
        assertThat((List<?>) result.get("citations")).extracting(Object::toString)
                .anyMatch(value -> value.contains("evidence:EV-1"));
        assertThat(result.get("iocValues")).asString().contains("10.1.2.3");
        verify(repository).save(any(InvestigationEntity.class));
        verify(audit, org.mockito.Mockito.atLeast(3)).publish(any());
    }

    @Test
    void appendingSummaryWithAnExplicitIncidentIsIdempotent() {
        TenantContext.set("tenant-a");
        InvestigationRepository repository = mock(InvestigationRepository.class);
        IncidentClient incidents = mock(IncidentClient.class);
        InvestigationEntity entity = new InvestigationEntity();
        entity.setId("INV-1");
        entity.setTenantId("tenant-a");
        entity.setAlertId("AL-1");
        entity.setStatus("COMPLETED");
        entity.setResultJson("{\"alertId\":\"AL-1\",\"alert\":{\"id\":\"AL-1\"},\"analysis\":\"bounded\",\"recommendedSpl\":\"host=h\",\"citations\":[]}");
        given(repository.findByIdAndTenantId("INV-1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.markAppended(anyString(), anyString(), anyString(), any(), anyString(), any())).willReturn(1);
        given(incidents.addNote(anyString(), anyString(), anyString(), anyString())).willReturn(ok("{}", SocpService.INCIDENT));
        AuditSink audit = mock(AuditSink.class);
        InvestigationAgentService service = new InvestigationAgentService(
                repository, mock(AlertClient.class), mock(SearchClient.class), incidents,
                mock(ThreatClient.class), mock(LlmChatClient.class), audit, properties());

        Map<String, Object> first = service.appendToIncident("INV-1", "CASE-1");
        Map<String, Object> second = service.appendToIncident("INV-1", "CASE-1");

        assertThat(first.get("summaryAppended")).isEqualTo(true);
        assertThat(second.get("duplicate")).isEqualTo(true);
        verify(incidents).addNote(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aLiveInvestigationCannotBeClaimedByASecondInstance() {
        TenantContext.set("tenant-a");
        InvestigationRepository repository = mock(InvestigationRepository.class);
        InvestigationEntity running = new InvestigationEntity();
        running.setId("INV-1");
        running.setTenantId("tenant-a");
        running.setAlertId("AL-1");
        running.setStatus("RUNNING");
        running.setResultJson("{}");
        given(repository.findByTenantIdAndAlertId("tenant-a", "AL-1")).willReturn(Optional.of(running));
        given(repository.claim(anyString(), anyString(), anyString(), any(), any())).willReturn(0);

        InvestigationAgentService service = new InvestigationAgentService(
                repository, mock(AlertClient.class), mock(SearchClient.class), mock(IncidentClient.class),
                mock(ThreatClient.class), mock(LlmChatClient.class), mock(AuditSink.class), properties());

        assertThatThrownBy(() -> service.investigate("AL-1"))
                .isInstanceOf(com.socp.platform.error.exception.ApiException.class)
                .extracting("code").isEqualTo(409);
    }

    private static InvestigationProperties properties() {
        InvestigationProperties properties = new InvestigationProperties();
        properties.setMaxToolCalls(6);
        properties.setMaxEvidence(20);
        properties.setMaxRelatedEvents(20);
        properties.setTimeoutMs(10_000);
        return properties;
    }

    private static ServiceCall ok(String body, SocpService service) {
        return new ServiceCall(service, "http://service", true, 200, body,
                null, 1, false, 1);
    }
}
