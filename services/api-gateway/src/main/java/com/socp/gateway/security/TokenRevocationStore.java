package com.socp.gateway.security;

import reactor.core.publisher.Mono;

import java.time.Instant;

/** Shared session revocation contract used by every gateway instance. */
public interface TokenRevocationStore {

    Mono<Void> revoke(String jti, Instant expiresAt);

    Mono<Boolean> isRevoked(String jti);
}
