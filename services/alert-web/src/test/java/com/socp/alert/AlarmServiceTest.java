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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

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

    @Mock
    private AlarmEvidenceRepository evidenceRepository;

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

    @Test
    void persistsEvidenceSnapshotAndIncludesItInOutbox() {
        TenantContext.set("tenant-a");
        Alarm alarm = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "10.0.0.9");
        given(repository.save(alarm)).willReturn(alarm);
        AlarmEvidenceInput input = new AlarmEvidenceInput("event-1", Instant.parse("2026-08-18T10:00:00Z"),
                "auth", "host-1", "HIGH", "failed login for admin",
                Map.of("user", "admin", "src_ip", "10.0.0.9"));

        service.create(alarm, List.of(input));

        ArgumentCaptor<Iterable> evidenceCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(evidenceRepository).saveAll(evidenceCaptor.capture());
        List<AlarmEvidence> saved = StreamSupport.stream(evidenceCaptor.getValue().spliterator(), false)
                .map(AlarmEvidence.class::cast).toList();
        assertEquals(1, saved.size());
        assertEquals("event-1", saved.get(0).getEventId());
        assertEquals("tenant-a", saved.get(0).getTenantId());
        assertEquals("10.0.0.9", saved.get(0).view().fields().get("src_ip"));
        verify(outboxRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getPayload().contains("\"eventId\":\"event-1\"")));
    }

    @Test
    void readsEvidenceOnlyForCurrentTenantAndBuildsDrilldownQuery() {
        TenantContext.set("tenant-a");
        Alarm alarm = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "10.0.0.9");
        AlarmEvidence evidence = AlarmEvidence.from("alarm-1", "tenant-a", 0,
                new AlarmEvidenceInput("event-1", Instant.parse("2026-08-18T10:00:00Z"),
                        "auth", "host-1", "HIGH", "failed login", Map.of()));
        given(repository.findByTenantIdAndId("tenant-a", "alarm-1")).willReturn(Optional.of(alarm));
        given(evidenceRepository.findByTenantIdAndAlarmIdOrderByEvidenceOrderAscIdAsc("tenant-a", "alarm-1"))
                .willReturn(List.of(evidence));

        AlarmEvidenceResponse response = service.evidence("alarm-1");

        assertEquals(1, response.total());
        assertTrue(response.complete());
        assertEquals("eventId=event-1", response.query());
        assertEquals("event-1", response.items().get(0).eventId());
    }

    @Test
    void repeatedSourceAlertIsIdempotent() {
        TenantContext.set("tenant-a");
        Alarm existing = new Alarm("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "sudo", "host-1");
        existing.setSourceAlertId("stable-alert-1");
        given(repository.findByTenantIdAndSourceAlertId("tenant-a", "stable-alert-1"))
                .willReturn(Optional.of(existing));

        Alarm duplicate = new Alarm("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "sudo", "host-1");
        duplicate.setSourceAlertId("stable-alert-1");

        assertEquals(existing, service.create(duplicate));
        org.mockito.Mockito.verify(outboxRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(OutboxEvent.class));
    }
}
