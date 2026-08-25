package com.socp.gateway.oidc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Development-only fallback. Production must use the Redis implementation. */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "socp.oidc.state.backend", havingValue = "memory")
public class InMemoryOidcStateStore implements OidcStateStore {

    private final Map<String, StoredEntry> states = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InMemoryOidcStateStore() {
        this(System::currentTimeMillis);
    }

    public InMemoryOidcStateStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Void> save(String state, Entry entry, Duration ttl) {
        return Mono.fromRunnable(() -> {
            long now = clock.getAsLong();
            states.entrySet().removeIf(item -> item.getValue().expiresAt() <= now);
            states.put(state, new StoredEntry(entry, now + Math.max(1L, ttl.toMillis())));
        });
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
