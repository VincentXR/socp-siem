package com.socp.alert;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.SoarClient;
import com.socp.platform.client.ThreatClient;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

    @Mock
    private AlarmRepository repository;

    @Mock
    private CkReporter ckReporter;

    @Mock
    private ThreatClient threatClient;

    @Mock
    private NotifyClient notifyClient;

    @Mock
    private IncidentClient incidentClient;

    @Mock
    private SoarClient soarClient;

    @Mock
    private OutboxRepository outboxRepository;

    @InjectMocks
    private AlarmService service;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createsAlarmAndPendingOutboxUnderCurrentTenant() {
        TenantContext.set("tenant-a");
        Alarm alarm = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", null);
        given(repository.save(alarm)).willReturn(alarm);

        Alarm saved = service.create(alarm);

        assertEquals("tenant-a", saved.getTenantId());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(event.capture());
        assertEquals("ALARM_CREATED", event.getValue().getEventType());
        assertEquals("PENDING", event.getValue().getStatus());
        assertTrue(event.getValue().getPayload().contains("AUTH-BRUTE"));
        assertTrue(event.getValue().getCreatedAt() != null);
    }
}
