package com.socp.alert;

import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpService;
import com.socp.platform.client.ThreatClient;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
}
