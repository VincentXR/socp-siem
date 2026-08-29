package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.rule.engine.WatchlistStateStore;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Value("${socp.detect.watchlists.cache-max-entries:10000}")
    private int maxCacheEntries = 10_000;

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
        if (cached != null) cache.remove(key, cached);

        State state = repository.findByTenantIdAndListName(tenantId, name)
                .map(this::toState)
                .orElse(null);
        cachePut(key, new CachedState(state, now + Math.max(0L, refreshMs)));
        return state;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> names(String tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(entity -> {
                    State state = toState(entity);
                    cachePut(new Key(entity.getTenantId(), entity.getListName()),
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
        cachePut(new Key(tenantId, name), new CachedState(toState(saved), expiresAt()));
    }

    @Override
    @Transactional
    public void delete(String tenantId, String name) {
        WatchlistEntity entity = repository.findByTenantIdAndListName(tenantId, name)
                .orElseGet(() -> new WatchlistEntity(tenantId, name));
        entity.markDeleted();
        WatchlistEntity saved = repository.save(entity);
        cachePut(new Key(tenantId, name), new CachedState(toState(saved), expiresAt()));
    }

    @Override
    @Transactional
    public void clear() {
        repository.deleteByTenantId(TenantContext.require());
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

    private void cachePut(Key key, CachedState state) {
        cache.put(key, state);
        if (cache.size() > Math.max(1, maxCacheEntries)) cleanupCache();
    }

    @Scheduled(fixedDelayString = "${socp.detect.watchlists.cache-cleanup-interval-ms:60000}")
    void cleanupCache() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        int excess = cache.size() - Math.max(1, maxCacheEntries);
        if (excess > 0) {
            cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingLong(CachedState::expiresAt)))
                    .limit(excess)
                    .forEach(entry -> cache.remove(entry.getKey(), entry.getValue()));
        }
    }

    int cachedEntries() {
        return cache.size();
    }

    private record Key(String tenantId, String name) {
    }

    private record CachedState(State state, long expiresAt) {
    }
}
