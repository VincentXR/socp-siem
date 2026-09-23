package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.WatchlistRepository;
import com.socp.detect.web.persistence.entity.WatchlistEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.rule.engine.WatchlistStateStore;
import com.socp.rule.engine.WatchlistLimits;
import com.socp.rule.engine.Watchlists;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;

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
    private final Map<Key, CachedState> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private long cacheWeight;

    @Value("${socp.detect.watchlists.refresh-ms:1000}")
    private long refreshMs;

    @Value("${socp.detect.watchlists.cache-max-entries:10000}")
    private int maxCacheEntries = 10_000;

    @Value("${socp.detect.watchlists.cache-max-weight:67108864}")
    private long maxCacheWeight = 64L * 1024 * 1024;

    PersistentWatchlistStateStore(WatchlistRepository repository, ObjectMapper objectMapper,
                                  JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        // Each retry needs a fresh transaction after an insert/optimistic conflict.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
    }

    @Override
    @Transactional(readOnly = true)
    public State find(String tenantId, String name) {
        Key key = new Key(tenantId, name);
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CachedState cached = cache.get(key);
            if (cached != null && cached.expiresAt() > now) return cached.state();
            invalidate(key);
        }

        State state = repository.findByTenantIdAndListName(tenantId, name)
                .map(this::toState)
                .orElse(null);
        cachePut(key, state, now + Math.max(0L, refreshMs));
        return state;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> names(String tenantId) {
        var names = repository.findNames(tenantId, PageRequest.of(0, WatchlistLimits.MAX_LISTS + 1));
        if (names.size() > WatchlistLimits.MAX_LISTS) {
            throw ApiException.of(413, "watchlist namespace exceeds the supported catalogue limit");
        }
        return Set.copyOf(names);
    }

    @Transactional(readOnly = true)
    public java.util.List<WatchlistRepository.Summary> summaries(String tenantId) {
        var result = repository.summaries(tenantId, PageRequest.of(0, WatchlistLimits.MAX_LISTS + 1));
        if (result.size() > WatchlistLimits.MAX_LISTS) {
            throw ApiException.of(413, "watchlist namespace exceeds the supported catalogue limit");
        }
        return result;
    }

    @Transactional(readOnly = true)
    public State findFresh(String tenantId, String name) {
        return repository.findByTenantIdAndListName(tenantId, name).map(this::toState).orElse(null);
    }

    @Override
    public State update(String tenantId, String name, UnaryOperator<State> mutation) {
        WatchlistLimits.name(name);
        State result = retryTransaction(() -> {
            lockNamespace(tenantId);
            WatchlistEntity current = repository.findByTenantIdAndListName(tenantId, name).orElse(null);
            State next = java.util.Objects.requireNonNull(mutation.apply(current == null ? null : toState(current)));
            WatchlistLimits.values(next.values());
            if (next.deleted() && !Watchlists.hasTemplate(name)) {
                repository.deleteByTenantIdAndListName(tenantId, name);
                return next;
            }
            if (current == null && repository.countByTenantId(tenantId) >= WatchlistLimits.MAX_LISTS) {
                throw ApiException.of(409, "watchlist namespace limit reached: " + WatchlistLimits.MAX_LISTS);
            }
            WatchlistEntity entity = current == null ? new WatchlistEntity(tenantId, name) : current;
            if (next.deleted()) entity.markDeleted();
            else entity.saveValues(serialize(next.values()), next.values().size());
            repository.saveAndFlush(entity);
            return next;
        });
        // Publish no speculative cache entry before the transaction commits.
        synchronized (cache) { invalidate(new Key(tenantId, name)); }
        return result;
    }

    private <T> T retryTransaction(Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (DataIntegrityViolationException | TransientDataAccessException conflict) {
                if (attempt >= 2) throw conflict;
            }
        }
    }

    private void lockNamespace(String tenantId) {
        var existing = jdbc.queryForList("select tenant_id from t_watchlist_namespace where tenant_id = ? for update",
                String.class, tenantId);
        if (existing.isEmpty()) {
            // A concurrent first writer may win this insert. Retry its loser in
            // a fresh transaction, which then takes the existing namespace lock.
            jdbc.update("insert into t_watchlist_namespace (tenant_id) values (?)", tenantId);
        }
    }

    @Override
    public void clear() {
        String tenant = TenantContext.require();
        retryTransaction(() -> { lockNamespace(tenant); return repository.deleteByTenantId(tenant); });
        synchronized (cache) { cache.clear(); cacheWeight = 0; }
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

    private void cachePut(Key key, State state, long expiresAt) {
        long weight = 128L + 2L * (key.tenantId().length() + key.name().length());
        if (state != null) for (String value : state.values()) weight += 128L + 2L * value.length();
        synchronized (cache) {
            invalidate(key);
            if (expiresAt <= System.currentTimeMillis() || weight > Math.max(1, maxCacheWeight)) return;
            cache.put(key, new CachedState(state, expiresAt, weight));
            cacheWeight += weight;
            var oldest = cache.entrySet().iterator();
            while (cache.size() > Math.max(1, maxCacheEntries) || cacheWeight > Math.max(1, maxCacheWeight)) {
                var entry = oldest.next();
                cacheWeight -= entry.getValue().weight();
                oldest.remove();
            }
        }
    }

    private void invalidate(Key key) {
        CachedState removed = cache.remove(key);
        if (removed != null) cacheWeight -= removed.weight();
    }

    @Scheduled(fixedDelayString = "${socp.detect.watchlists.cache-cleanup-interval-ms:60000}")
    void cleanupCache() {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            var entries = cache.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (entry.getValue().expiresAt() <= now) {
                    cacheWeight -= entry.getValue().weight();
                    entries.remove();
                }
            }
        }
    }

    int cachedEntries() {
        synchronized (cache) { return cache.size(); }
    }

    long cachedWeight() { synchronized (cache) { return cacheWeight; } }

    private record Key(String tenantId, String name) {
    }

    private record CachedState(State state, long expiresAt, long weight) {
    }
}
