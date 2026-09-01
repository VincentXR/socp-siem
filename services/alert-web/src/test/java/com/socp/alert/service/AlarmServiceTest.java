package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmEvidence;
import com.socp.alert.domain.AlarmEvidenceInput;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.OutboxEvent;
import com.socp.alert.domain.Severity;
import com.socp.alert.api.request.CreateAlarmRequest;
import com.socp.alert.persistence.entity.AlarmBatchIdempotency;
import com.socp.alert.api.response.AlarmEvidenceResponse;
import com.socp.alert.persistence.repository.AlarmEvidenceRepository;
import com.socp.alert.persistence.repository.AlarmBatchIdempotencyRepository;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.persistence.repository.OutboxRepository;


import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
    private AlarmEnrichmentService enrichmentService;

    @Mock
    private AlarmQueryService queryService;

    @Mock
    private AlarmStatisticsService statisticsService;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AlarmEvidenceRepository evidenceRepository;

    @Mock
    private AlarmDeliveryRegistrar deliveryRegistrar;

    @Mock
    private AlarmBatchIdempotencyRepository batchIdempotencyRepository;

    @InjectMocks
    private AlarmService service;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        service.stopEnrichment();
    }

    @Test
    void createsAlarmAndPendingOutboxUnderCurrentTenant() {
        TenantContext.set("tenant-a");
        Alarm alarm = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", null);
        given(repository.save(alarm)).willAnswer(invocation -> {
            alarm.setId("alarm-tenant-a");
            return alarm;
        });

        Alarm saved = service.create(alarm);

        assertEquals("tenant-a", saved.getTenantId());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(event.capture());
        assertEquals("ALARM_CREATED", event.getValue().getEventType());
        assertEquals("PENDING", event.getValue().getStatus());
        assertTrue(event.getValue().getPayload().contains("AUTH-BRUTE"));
        assertTrue(event.getValue().getPayload().contains("\"tenantId\":\"tenant-a\""));
        assertTrue(event.getValue().getCreatedAt() != null);
        verify(deliveryRegistrar).register("tenant-a", "alarm-tenant-a", event.getValue().getPayload());
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

    @Test
    void delegatesDescendingTimestampSortToTheDatabaseQuery() {
        TenantContext.set("tenant-a");
        Alarm legacy = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "legacy", "host-legacy");
        legacy.setAlertCreatedAt(null);
        Alarm fresh = new Alarm("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "fresh", "host-fresh");
        fresh.setAlertCreatedAt(Instant.parse("2026-08-19T14:00:00Z"));
        given(repository.list(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.any(AlarmQuery.class)))
                .willReturn(List.of(legacy, fresh));

        List<Alarm> result = new AlarmQueryService(repository).query(null, null, null, null,
                "alertCreatedAt", "descending");

        assertEquals(List.of(legacy, fresh), result);
        ArgumentCaptor<AlarmQuery> query = ArgumentCaptor.forClass(AlarmQuery.class);
        verify(repository).list(org.mockito.ArgumentMatchers.eq("tenant-a"), query.capture());
        assertEquals(AlarmQuery.SortField.ALERT_CREATED_AT, query.getValue().sort());
        assertEquals(false, query.getValue().ascending());
    }

    @Test
    void delegatesThreatEnrichmentSchedulingAfterTheTransactionalWrite() {
        TenantContext.set("tenant-a");
        Alarm alarm = new Alarm("IOC-MATCH", "IOC match", Severity.HIGH,
                "connection to 203.0.113.10", "203.0.113.10");
        given(repository.save(alarm)).willAnswer(invocation -> {
            alarm.setId("alarm-tenant-a");
            return alarm;
        });
        service.create(alarm);

        verify(enrichmentService).scheduleAfterCommit(alarm);
    }

    @Test
    void batchPersistsResponseAndReplaysItForTheSameRequest() {
        TenantContext.set("tenant-a");
        CreateAlarmRequest request = request("source-1");
        given(batchIdempotencyRepository.findByTenantIdAndIdempotencyKey("tenant-a", "batch-1"))
                .willReturn(Optional.empty());
        given(repository.findByTenantIdAndSourceAlertId("tenant-a", "source-1"))
                .willReturn(Optional.empty());
        AtomicInteger ids = new AtomicInteger();
        given(repository.save(org.mockito.ArgumentMatchers.any(Alarm.class))).willAnswer(invocation -> {
            Alarm alarm = invocation.getArgument(0);
            alarm.setId("alarm-" + ids.incrementAndGet());
            return alarm;
        });

        Map<String, Object> first = service.createBatch(List.of(request), "batch-1");

        assertEquals(List.of("alarm-1"), first.get("accepted"));
        ArgumentCaptor<AlarmBatchIdempotency> row = ArgumentCaptor.forClass(AlarmBatchIdempotency.class);
        verify(batchIdempotencyRepository).saveAndFlush(row.capture());
        given(batchIdempotencyRepository.findByTenantIdAndIdempotencyKey("tenant-a", "batch-1"))
                .willReturn(Optional.of(row.getValue()));

        Map<String, Object> replay = service.createBatch(List.of(request), "batch-1");
        assertEquals(first, replay);
        verify(repository, org.mockito.Mockito.times(1)).save(org.mockito.ArgumentMatchers.any(Alarm.class));
    }

    @Test
    void batchRejectsChangedPayloadForAnExistingIdempotencyKey() {
        TenantContext.set("tenant-a");
        CreateAlarmRequest request = request("source-1");
        AlarmBatchIdempotency row = new AlarmBatchIdempotency();
        row.setRequestHash("different");
        row.setResponseJson("{}");
        given(batchIdempotencyRepository.findByTenantIdAndIdempotencyKey("tenant-a", "batch-1"))
                .willReturn(Optional.of(row));

        org.junit.jupiter.api.Assertions.assertThrows(com.socp.platform.error.exception.ApiException.class,
                () -> service.createBatch(List.of(request), "batch-1"));
    }

    @Test
    void similarAlertsStayTenantAndRuleEntityScoped() {
        TenantContext.set("tenant-a");
        Alarm source = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "host-1");
        source.setId("alarm-1");
        Alarm candidate = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "host-1");
        candidate.setId("alarm-2");
        given(repository.findByTenantIdAndId("tenant-a", "alarm-1")).willReturn(Optional.of(source));
        given(repository.findSimilar(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("alarm-1"), org.mockito.ArgumentMatchers.eq("AUTH-BRUTE"),
                org.mockito.ArgumentMatchers.eq("host-1"), org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(candidate));

        assertEquals(List.of(candidate), service.similar("alarm-1", 500));
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(repository).findSimilar(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("alarm-1"), org.mockito.ArgumentMatchers.eq("AUTH-BRUTE"),
                org.mockito.ArgumentMatchers.eq("host-1"), pageable.capture());
        assertEquals(100, pageable.getValue().getPageSize());
    }

    private static CreateAlarmRequest request(String sourceAlertId) {
        return new CreateAlarmRequest("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "host-1", null, null, null, List.of(), sourceAlertId,
                null, null, null, null, null);
    }
}
