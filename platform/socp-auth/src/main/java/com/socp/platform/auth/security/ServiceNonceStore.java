package com.socp.platform.auth.security;

import java.time.Duration;

/** Atomic replay guard for signed internal service requests. */
public interface ServiceNonceStore {

    ClaimResult claim(String service, String nonce, Duration ttl);

    enum ClaimResult {
        CLAIMED,
        REPLAYED,
        UNAVAILABLE
    }
}
