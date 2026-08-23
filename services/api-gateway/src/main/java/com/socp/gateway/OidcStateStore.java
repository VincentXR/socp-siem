package com.socp.gateway;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** One-time PKCE state storage shared by OIDC login and callback handlers. */
interface OidcStateStore {

    Mono<Void> save(String state, Entry entry, Duration ttl);

    /** Atomically returns and consumes a state value. */
    Mono<Entry> consume(String state);

    record Entry(String verifier, String nonce, long expiresAt) {
    }
}
