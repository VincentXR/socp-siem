package com.socp.gateway.oidc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Development-only fallback. Production must use the Redis implementation. */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "socp.oidc.state.backend", havingValue = "memory")
public class InMemoryOidcStateStore implements OidcStateStore {

    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private final Map<String, StoredEntry> states = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final int maxEntries;

    public InMemoryOidcStateStore() {
        this(System::currentTimeMillis, DEFAULT_MAX_ENTRIES);
    }

    public InMemoryOidcStateStore(LongSupplier clock) {
        this(clock, DEFAULT_MAX_ENTRIES);
    }

    InMemoryOidcStateStore(LongSupplier clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = Math.max(1, Math.min(1_000_000, maxEntries));
    }

    @Override
    public Mono<Void> save(String state, Entry entry, Duration ttl) {
        return Mono.fromRunnable(() -> {
            long now = clock.getAsLong();
            states.entrySet().removeIf(item -> item.getValue().expiresAt() <= now);
            states.put(state, new StoredEntry(entry, now + Math.max(1L, ttl.toMillis())));
            trimToLimit();
        });
    }

    private void trimToLimit() {
        int excess = states.size() - maxEntries;
        if (excess <= 0) return;
        states.entrySet().stream()
                .sorted(Comparator.comparingLong(item -> item.getValue().expiresAt()))
                .limit(excess)
                .forEach(item -> states.remove(item.getKey(), item.getValue()));
    }

    @Override
    public Mono<Entry> consume(String state) {
        return Mono.defer(() -> {
            StoredEntry stored = states.remove(state);
            if (stored == null || stored.expiresAt() <= clock.getAsLong()
                    || stored.entry().expiresAt() <= clock.getAsLong()) {
                return Mono.empty();
            }
            return Mono.just(stored.entry());
        });
    }

    private record StoredEntry(Entry entry, long expiresAt) {
    }
}
