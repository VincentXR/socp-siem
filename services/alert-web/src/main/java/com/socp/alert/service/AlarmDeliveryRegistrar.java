package com.socp.alert.service;

import com.socp.alert.domain.AlarmDelivery;
import com.socp.alert.domain.AlarmDeliveryDestination;
import com.socp.alert.repository.AlarmDeliveryRepository;


import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class AlarmDeliveryRegistrar {

    private final AlarmDeliveryRepository repository;

    public AlarmDeliveryRegistrar(AlarmDeliveryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void register(String tenantId, String alarmId, String payload) {
        if (!TenantContext.isValid(tenantId)) throw new IllegalArgumentException("invalid alarm tenant");
        if (alarmId == null || alarmId.isBlank()) throw new IllegalArgumentException("missing alarm id");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("missing alarm payload");

        String current = TenantContext.get();
        if (!TenantContext.isSystemScope() && current != null && !tenantId.equals(current)) {
            throw new IllegalArgumentException("alarm tenant does not match the current tenant scope");
        }
        TenantContext.runWith(tenantId, () -> registerInScope(tenantId, alarmId, payload));
    }

    private void registerInScope(String tenantId, String alarmId, String payload) {
        List<AlarmDelivery> candidates = java.util.Arrays.stream(AlarmDeliveryDestination.values())
                .map(destination -> pending(tenantId, alarmId, destination, payload))
                .toList();
        Set<String> existing = new HashSet<>();
        repository.findByTenantIdAndIdIn(tenantId, candidates.stream().map(AlarmDelivery::getId).toList())
                .forEach(delivery -> existing.add(delivery.getId()));
        List<AlarmDelivery> missing = candidates.stream()
                .filter(delivery -> !existing.contains(delivery.getId()))
                .toList();
        if (!missing.isEmpty()) repository.saveAll(missing);
    }

    /** Diagnostic view used by operators and release evidence; payload is never returned. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> status(String tenantId, String alarmId) {
        if (!TenantContext.isValid(tenantId)) throw new IllegalArgumentException("invalid alarm tenant");
        String current = TenantContext.get();
        if (!TenantContext.isSystemScope() && current != null && !tenantId.equals(current)) {
            throw new IllegalArgumentException("alarm tenant does not match the current tenant scope");
        }
        return TenantContext.callWith(tenantId, () -> repository.findByTenantIdAndAlarmIdOrderByDestinationAsc(tenantId, alarmId).stream()
                .map(delivery -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("deliveryId", delivery.getId());
                    row.put("tenantId", delivery.getTenantId());
                    row.put("alarmId", delivery.getAlarmId());
                    row.put("destination", delivery.getDestination());
                    row.put("status", delivery.getStatus());
                    row.put("attempts", delivery.getAttempts());
                    row.put("nextAttemptAt", delivery.getNextAttemptAt());
                    row.put("claimedAt", delivery.getClaimedAt());
                    row.put("deliveredAt", delivery.getDeliveredAt());
                    if (delivery.getLastError() != null) row.put("lastError", delivery.getLastError());
                    return row;
                }).toList());
    }

    private static AlarmDelivery pending(String tenantId, String alarmId,
                                         AlarmDeliveryDestination destination, String payload) {
        Instant now = Instant.now();
        AlarmDelivery delivery = new AlarmDelivery();
        String key = tenantId + "\u0000" + alarmId + "\u0000" + destination.name();
        delivery.setId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
        delivery.setTenantId(tenantId);
        delivery.setAlarmId(alarmId);
        delivery.setDestination(destination.name());
        delivery.setPayload(payload);
        delivery.setStatus("PENDING");
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(now);
        delivery.setTraceId(MDC.get("traceId"));
        delivery.setCreatedAt(now);
        delivery.setUpdatedAt(now);
        return delivery;
    }
}
