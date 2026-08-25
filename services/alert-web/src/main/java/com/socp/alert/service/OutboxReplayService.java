package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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

    public OutboxReplayService(OutboxRepository eventRepository,
                               AlarmDeliveryRepository deliveryRepository,
                               AlarmRepository alarmRepository) {
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.alarmRepository = alarmRepository;
    }

    @Transactional
    public OutboxReplayResult requeueAlarmEvent(String id) {
        String tenant = TenantContext.require();
        OutboxEvent event = eventRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Alarm event outbox row does not exist: " + id));
        // Outbox events predate a tenant_id column. Resolve the aggregate through
        // the tenant-scoped alarm repository before allowing an operator action.
        alarmRepository.findByTenantIdAndId(tenant, event.getAggregateId())
                .orElseThrow(() -> ApiException.notFound("Alarm event outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (eventRepository.requeueDead(id, now) != 1) {
            throw ApiException.badRequest("Only DEAD alarm event outbox rows can be requeued");
        }
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
        return new OutboxReplayResult(id, "ALARM_DELIVERY", tenant, "PENDING", now);
    }
}
