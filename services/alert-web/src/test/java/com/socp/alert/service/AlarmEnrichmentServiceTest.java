package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.repository.AlarmRepository;


import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmEnrichmentServiceTest {

    private AlarmEnrichmentService service;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        if (service != null) service.stop();
    }

    @Test
    void startsOnlyAfterTheAlarmTransactionCommits() {
        AlarmRepository repository = mock(AlarmRepository.class);
        ThreatClient threatClient = mock(ThreatClient.class);
        service = new AlarmEnrichmentService(repository, threatClient, 1, 100);
        Alarm alarm = new Alarm("IOC-MATCH", "IOC match", Severity.HIGH,
                "connection to 203.0.113.10", "203.0.113.10");
        alarm.setId("alarm-tenant-a");
        alarm.setTenantId("tenant-a");
        when(threatClient.matchIocs(anyString())).thenReturn(
                new ServiceCall(SocpService.THREAT, "http://threat", true,
                        200, "{\"hits\":{}}", null, 1, false, 1));
        TransactionSynchronizationManager.initSynchronization();

        service.scheduleAfterCommit(alarm);

        verify(threatClient, never()).matchIocs(anyString());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        verify(threatClient, timeout(1_000)).matchIocs(anyString());
    }

    @Test
    void enrichesCandidatesRecalculatesRiskAndPersistsTheProjection() {
        AlarmRepository repository = mock(AlarmRepository.class);
        ThreatClient threatClient = mock(ThreatClient.class);
        service = new AlarmEnrichmentService(repository, threatClient, 1, 100);
        Alarm alarm = new Alarm("R-1", "IOC match", Severity.HIGH,
                "connection to 203.0.113.10 and evil.example.com", "203.0.113.10");
        alarm.setId("alarm-tenant-a");
        alarm.setTenantId("tenant-a");
        alarm.setMitre("T1110");
        when(threatClient.matchIocs(anyString())).thenReturn(new ServiceCall(
                SocpService.THREAT, "http://threat", true, 200,
                "{\"hits\":[{\"ioc\":\"203.0.113.10\"},{\"ioc\":\"evil.example.com\"}]}",
                null, 1, false, 1));
        when(repository.countRecentByEntity(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(2L);
        when(repository.findByTenantIdAndId("tenant-a", "alarm-tenant-a"))
                .thenReturn(Optional.of(alarm));
        when(repository.updateEnrichment(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString())).thenReturn(1);

        service.enrich(alarm);

        verify(threatClient).matchIocs(org.mockito.ArgumentMatchers.argThat(json ->
                json.contains("203.0.113.10") && json.contains("evil.example.com")));
        verify(repository).updateEnrichment(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("alarm-tenant-a"),
                org.mockito.ArgumentMatchers.contains("203.0.113.10"),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).save(alarm);
    }

    @Test
    void ignoresMissingCandidatesUnavailableThreatResponsesAndInvalidHits() {
        AlarmRepository repository = mock(AlarmRepository.class);
        ThreatClient threatClient = mock(ThreatClient.class);
        service = new AlarmEnrichmentService(repository, threatClient, 1, 100);

        Alarm noCandidate = new Alarm("R-1", "No IOC", Severity.INFO, "plain message", "");
        noCandidate.setTenantId("tenant-a");
        service.enrich(noCandidate);
        verify(threatClient, org.mockito.Mockito.never()).matchIocs(anyString());

        Alarm alarm = new Alarm("R-1", "IOC", Severity.INFO, "1.2.3.4", null);
        alarm.setId("alarm-2");
        alarm.setTenantId("tenant-a");
        when(threatClient.matchIocs(anyString())).thenReturn(null);
        service.enrich(alarm);
        when(threatClient.matchIocs(anyString())).thenReturn(new ServiceCall(
                SocpService.THREAT, "http://threat", false, 503, "", "unavailable", 1, true, 1));
        service.enrich(alarm);
        when(threatClient.matchIocs(anyString())).thenReturn(new ServiceCall(
                SocpService.THREAT, "http://threat", true, 200, "not-json", null, 1, false, 1));
        service.enrich(alarm);

        verify(repository, org.mockito.Mockito.never()).save(alarm);
    }

    @Test
    void patchesOnlyEnrichmentFieldsOnTheLatestAlarm() {
        AlarmRepository repository = mock(AlarmRepository.class);
        ThreatClient threatClient = mock(ThreatClient.class);
        service = new AlarmEnrichmentService(repository, threatClient, 1, 100);
        Alarm stale = new Alarm("R-1", "IOC", Severity.HIGH, "1.2.3.4", "1.2.3.4");
        stale.setId("alarm-3");
        stale.setTenantId("tenant-a");
        stale.setStatus("OPEN");
        Alarm latest = new Alarm("R-1", "IOC", Severity.HIGH, "1.2.3.4", "1.2.3.4");
        latest.setId("alarm-3");
        latest.setTenantId("tenant-a");
        latest.setStatus("CLOSED");
        when(threatClient.matchIocs(anyString())).thenReturn(new ServiceCall(
                SocpService.THREAT, "http://threat", true, 200,
                "{\"hits\":[{\"ioc\":\"1.2.3.4\"}]}", null, 1, false, 1));
        when(repository.findByTenantIdAndId("tenant-a", "alarm-3")).thenReturn(Optional.of(latest));
        when(repository.updateEnrichment(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString())).thenReturn(1);

        service.enrich(stale);

        verify(repository).updateEnrichment(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("alarm-3"), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        assertThat(latest.getStatus()).isEqualTo("CLOSED");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(Alarm.class));
    }

    @Test
    void riskScoringFailsOpenWhenRecentAlarmLookupIsUnavailable() {
        AlarmRepository repository = mock(AlarmRepository.class);
        ThreatClient threatClient = mock(ThreatClient.class);
        service = new AlarmEnrichmentService(repository, threatClient, 1, 100);
        Alarm alarm = new Alarm();
        alarm.setTenantId("tenant-a");
        alarm.setEntity("host-1");
        alarm.setSeverity(null);
        when(repository.countRecentByEntity(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(service.score(alarm, 2)).isNotNull();
    }
}
