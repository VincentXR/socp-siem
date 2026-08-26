package com.socp.hips.web.persistence.store;



import com.socp.hips.web.persistence.store.*;
import com.socp.hips.web.persistence.repository.*;
import com.socp.hips.web.persistence.entity.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tenant-scoped endpoint events backed by the durable hips-web database. */
@Service
public class EndpointEventStore {

    private static final Logger log = LoggerFactory.getLogger(EndpointEventStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final EndpointStore endpoints;
    private final EndpointEventRepository repository;
    private final ObjectMapper objectMapper;

    public EndpointEventStore(EndpointStore endpoints, EndpointEventRepository repository,
                              ObjectMapper objectMapper) {
        this.endpoints = endpoints;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> add(Map<String, Object> input) {
        String tenant = tenant();
        String eventId = UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        String hostname = String.valueOf(input == null ? "" : input.getOrDefault("hostname", ""));
        Map<String, Object> event = new LinkedHashMap<>(input == null ? Map.of() : input);
        event.put("eventId", eventId);
        event.put("receivedAt", receivedAt.toString());
        event.put("tenantId", tenant);
        repository.save(new EndpointEventEntity(eventId, tenant, hostname, receivedAt, writeJson(event)));

        endpoints.list().stream()
                .filter(endpoint -> endpoint.hostname().equals(hostname))
                .findFirst()
                .ifPresent(endpoint -> endpoints.heartbeat(endpoint.id()));
        return event;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repository.findTop200ByTenantIdOrderByReceivedAtDesc(tenant()).stream()
                .map(this::readRecord)
                .toList();
    }

    private Map<String, Object> readRecord(EndpointEventEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayloadJson(), MAP_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Skipping malformed endpoint event id={}", entity.getEventId(), ex);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("eventId", entity.getEventId());
            fallback.put("tenantId", entity.getTenantId());
            fallback.put("receivedAt", entity.getReceivedAt().toString());
            return fallback;
        }
    }

    private String writeJson(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("endpoint event cannot be serialized", ex);
        }
    }

    private static String tenant() {
        return TenantContext.require();
    }
}
