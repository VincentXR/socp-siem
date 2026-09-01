package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.OutboxEvent;
import com.socp.alert.domain.OutboxReplayResult;
import com.socp.alert.persistence.repository.AlarmDeliveryRepository;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.persistence.repository.OutboxRepository;


import com.socp.platform.data.outbox.DeadOutboxRecord;
import com.socp.platform.data.outbox.OutboxAdminResult;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Tenant-scoped operator recovery for terminal outbox rows.
 *
 * <p>Rows only become replayable after the publisher has deliberately marked
 * them {@code DEAD}. Requeueing clears the old error and resets the retry
 * budget, making the next scheduled publisher scan own delivery again.</p>
 */
@Service
public class OutboxReplayService {

    private final OutboxRepository eventRepository;
    private final AlarmDeliveryRepository deliveryRepository;
    private final AlarmRepository alarmRepository;
    private final AlertPerformanceMetrics performanceMetrics;

    @Autowired
    public OutboxReplayService(OutboxRepository eventRepository,
                               AlarmDeliveryRepository deliveryRepository,
                               AlarmRepository alarmRepository,
                               AlertPerformanceMetrics performanceMetrics) {
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.alarmRepository = alarmRepository;
        this.performanceMetrics = performanceMetrics;
    }

    public OutboxReplayService(OutboxRepository eventRepository,
                               AlarmDeliveryRepository deliveryRepository,
                               AlarmRepository alarmRepository) {
        this(eventRepository, deliveryRepository, alarmRepository, null);
    }

    @Transactional(readOnly = true)
    public List<DeadOutboxRecord> deadAlarmEvents() {
        String tenant = TenantContext.require();
        return eventRepository.findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")
                .stream()
                .map(event -> new DeadOutboxRecord(event.getId(), "alarm_event",
                        event.getAggregateId(), event.getAttempts(), event.getCreatedAt(),
                        event.getUpdatedAt(), event.getLastError()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeadOutboxRecord> deadAlarmDeliveries() {
        String tenant = TenantContext.require();
        return deliveryRepository.findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")
                .stream()
                .map(delivery -> new DeadOutboxRecord(delivery.getId(), "alarm_delivery",
                        delivery.getAlarmId() + ':' + delivery.getDestination(), delivery.getAttempts(),
                        delivery.getCreatedAt(), delivery.getUpdatedAt(), delivery.getLastError()))
                .toList();
    }

    @Transactional
    public OutboxReplayResult requeueAlarmEvent(String id) {
        String tenant = TenantContext.require();
        OutboxEvent event = eventRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Alarm event outbox row does not exist: " + id));
        alarmRepository.findByTenantIdAndId(tenant, event.getAggregateId())
                .orElseThrow(() -> ApiException.notFound("Alarm event outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (eventRepository.requeueDead(id, tenant, now) != 1) {
            throw ApiException.badRequest("Only DEAD alarm event outbox rows can be requeued");
        }
        lifecycle("alarm_event", "requeued");
        return new OutboxReplayResult(id, "ALARM_EVENT", tenant, "PENDING", now);
    }

    @Transactional
    public OutboxReplayResult requeueAlarmDelivery(String id) {
        String tenant = TenantContext.require();
        deliveryRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Alarm delivery row does not exist: " + id));
        Instant now = Instant.now();
        if (deliveryRepository.requeueDead(id, tenant, now) != 1) {
            throw ApiException.badRequest("Only DEAD alarm delivery rows can be requeued");
        }
        lifecycle("alarm_delivery", "requeued");
        return new OutboxReplayResult(id, "ALARM_DELIVERY", tenant, "PENDING", now);
    }

    @Transactional
    public OutboxAdminResult discardAlarmEvent(String id, String reason) {
        String tenant = TenantContext.require();
        OutboxEvent event = eventRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Alarm event outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (eventRepository.discardDead(id, tenant,
                discardReason(reason, event.getLastError()), now) != 1) {
            throw ApiException.badRequest("Only DEAD alarm event outbox rows can be discarded");
        }
        lifecycle("alarm_event", "discarded");
        return new OutboxAdminResult(id, "alarm_event", "DISCARDED", now);
    }

    @Transactional
    public OutboxAdminResult discardAlarmDelivery(String id, String reason) {
        String tenant = TenantContext.require();
        var delivery = deliveryRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Alarm delivery row does not exist: " + id));
        Instant now = Instant.now();
        if (deliveryRepository.discardDead(id, tenant,
                discardReason(reason, delivery.getLastError()), now) != 1) {
            throw ApiException.badRequest("Only DEAD alarm delivery rows can be discarded");
        }
        lifecycle("alarm_delivery", "discarded");
        return new OutboxAdminResult(id, "alarm_delivery", "DISCARDED", now);
    }

    private void lifecycle(String outbox, String outcome) {
        if (performanceMetrics != null) performanceMetrics.outboxLifecycle(outbox, outcome, 1);
    }

    private static String discardReason(String reason, String previousFailure) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A discard reason is required");
        }
        String value = "operator discard: " + reason.trim();
        if (previousFailure != null && !previousFailure.isBlank()) {
            value += " | previous failure: " + previousFailure.trim();
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
