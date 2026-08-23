package com.socp.detect.web.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.rule.engine.WatchlistStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL/H2-backed watchlist state. Reads use a tiny bounded-staleness
 * cache so rule evaluation does not turn every event into a database query;
 * any node observes a mutation made by another node after the configured
 * refresh interval.
 */
@Component
public class PersistentWatchlistStateStore implements WatchlistStateStore {

    private static final TypeReference<LinkedHashSet<String>> STRING_SET = new TypeReference<>() {
    };

    private final WatchlistRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<Key, CachedState> cache = new ConcurrentHashMap<>();

    @Value("${socp.detect.watchlists.refresh-ms:1000}")
    private long refreshMs;

    PersistentWatchlistStateStore(WatchlistRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public State find(String tenantId, String name) {
        Key key = new Key(tenantId, name);
        CachedState cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) return cached.state();

        State state = repository.findByTenantIdAndListName(tenantId, name)
                .map(this::toState)
                .orElse(null);
        cache.put(key, new CachedState(state, now + Math.max(0L, refreshMs)));
        return state;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> names(String tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(entity -> {
                    State state = toState(entity);
                    cache.put(new Key(entity.getTenantId(), entity.getListName()),
                            new CachedState(state, System.currentTimeMillis() + Math.max(0L, refreshMs)));
                    return entity.getListName();
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional
    public void save(String tenantId, String name, Set<String> values) {
        WatchlistEntity entity = repository.findByTenantIdAndListName(tenantId, name)
                .orElseGet(() -> new WatchlistEntity(tenantId, name));
        entity.saveValues(serialize(values));
        WatchlistEntity saved = repository.save(entity);
        cache.put(new Key(tenantId, name), new CachedState(toState(saved), expiresAt()));
    }

    @Override
    @Transactional
    public void delete(String tenantId, String name) {
        WatchlistEntity entity = repository.findByTenantIdAndListName(tenantId, name)
                .orElseGet(() -> new WatchlistEntity(tenantId, name));
        entity.markDeleted();
        WatchlistEntity saved = repository.save(entity);
        cache.put(new Key(tenantId, name), new CachedState(toState(saved), expiresAt()));
    }

    @Override
    @Transactional
    public void clear() {
        repository.deleteAll();
        cache.clear();
    }

    private State toState(WatchlistEntity entity) {
        return new State(entity.isDeleted() ? Set.of() : deserialize(entity.getValuesJson()), entity.isDeleted());
    }

    private String serialize(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Set.of() : values);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to persist watchlist values", failure);
        }
    }

    private Set<String> deserialize(String valuesJson) {
        try {
            return Set.copyOf(objectMapper.readValue(valuesJson, STRING_SET));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to read persisted watchlist values", failure);
        }
    }

    private long expiresAt() {
        return System.currentTimeMillis() + Math.max(0L, refreshMs);
    }

    private record Key(String tenantId, String name) {
    }

    private record CachedState(State state, long expiresAt) {
    }
}
