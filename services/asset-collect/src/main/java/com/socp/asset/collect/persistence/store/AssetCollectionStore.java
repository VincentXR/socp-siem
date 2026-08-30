package com.socp.asset.collect.persistence.store;


import com.socp.asset.collect.persistence.repository.AssetCollectionRepository;
import com.socp.asset.collect.persistence.entity.AssetCollectionEntity;
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

/** Persists the accepted asset envelope so forwarding can be retried after a restart. */
@Service
public class AssetCollectionStore {

    private static final Logger log = LoggerFactory.getLogger(AssetCollectionStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final AssetCollectionRepository repository;
    private final ObjectMapper objectMapper;

    public AssetCollectionStore(AssetCollectionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> append(String tenantId, Map<String, Object> source) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String id = UUID.randomUUID().toString();
        Instant collectedAt = Instant.now();
        Map<String, Object> record = new LinkedHashMap<>(source == null ? Map.of() : source);
        record.put("id", id);
        record.put("collectedAt", collectedAt.toString());
        record.put("tenantId", tenant);

        AssetCollectionEntity entity = new AssetCollectionEntity();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setPayloadJson(writeJson(record));
        entity.setCollectedAt(collectedAt);
        repository.save(entity);
        return record;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        return repository.findTop200ByTenantIdOrderByCollectedAtDesc(tenant).stream()
                .map(this::readRecord)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBySource(String tenantId, String source) {
        return list(tenantId).stream()
                .filter(record -> source == null || source.equals(record.get("source")))
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        return repository.countByTenantId(tenant);
    }

    private Map<String, Object> readRecord(AssetCollectionEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayloadJson(), MAP_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Skipping malformed persisted asset collection id={}", entity.getId(), ex);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("id", entity.getId());
            fallback.put("tenantId", entity.getTenantId());
            fallback.put("collectedAt", entity.getCollectedAt().toString());
            return fallback;
        }
    }

    private String writeJson(Map<String, Object> record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Asset collection cannot be serialized", ex);
        }
    }
}
