package com.socp.hips.collect.persistence.store;


import com.socp.hips.collect.persistence.repository.HipsEventRepository;
import com.socp.hips.collect.persistence.entity.HipsEventEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists event envelopes and keeps the API payload compatible with the Falco source shape. */
@Service
public class HipsEventStore {

    private static final Logger log = LoggerFactory.getLogger(HipsEventStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final HipsEventRepository repository;
    private final ObjectMapper objectMapper;

    public HipsEventStore(HipsEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> append(String tenantId, Map<String, Object> source) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String id = UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        Map<String, Object> record = new LinkedHashMap<>(source == null ? Map.of() : source);
        record.put("id", id);
        record.put("receivedAt", receivedAt.toString());
        record.put("tenantId", tenant);

        HipsEventEntity entity = new HipsEventEntity();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setReceivedAt(receivedAt);
        entity.setPayloadJson(writeJson(record));
        repository.save(entity);
        return record;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        return repository.findTop200ByTenantIdOrderByReceivedAtDesc(tenant).stream()
                .map(this::readRecord)
                .toList();
    }

    private Map<String, Object> readRecord(HipsEventEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayloadJson(), MAP_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Skipping malformed persisted HIPS event id={}", entity.getId(), ex);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("id", entity.getId());
            fallback.put("tenantId", entity.getTenantId());
            fallback.put("receivedAt", entity.getReceivedAt().toString());
            return fallback;
        }
    }

    private String writeJson(Map<String, Object> record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("HIPS event cannot be serialized", ex);
        }
    }
}
