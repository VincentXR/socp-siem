package com.socp.gateway.oidc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InMemoryOidcStateStoreTest {

    @Test
    void consumeIsAtomicAndOneTime() {
        AtomicLong now = new AtomicLong(1_000L);
        InMemoryOidcStateStore store = new InMemoryOidcStateStore(now::get);
        OidcStateStore.Entry entry = new OidcStateStore.Entry("verifier", "nonce", 10_000L);

        store.save("a".repeat(43), entry, Duration.ofMinutes(10)).block();

        assertEquals(entry, store.consume("a".repeat(43)).block());
        assertNull(store.consume("a".repeat(43)).block());
    }

    @Test
    void expiredStateIsRejectedAndRemoved() {
        AtomicLong now = new AtomicLong(1_000L);
        InMemoryOidcStateStore store = new InMemoryOidcStateStore(now::get);
        String state = "b".repeat(43);
        store.save(state, new OidcStateStore.Entry("verifier", "nonce", 2_000L), Duration.ofMinutes(10)).block();

        now.set(2_001L);

        assertNull(store.consume(state).block());
        assertNull(store.consume(state).block());
    }
}
