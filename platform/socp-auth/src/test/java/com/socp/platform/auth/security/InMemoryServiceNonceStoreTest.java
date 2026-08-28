package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryServiceNonceStoreTest {

    @Test
    void claimsEachServiceNonceOnlyOnce() {
        InMemoryServiceNonceStore store = new InMemoryServiceNonceStore();

        assertThat(store.claim("alert-web", "nonce-1", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.CLAIMED);
        assertThat(store.claim("alert-web", "nonce-1", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.REPLAYED);
        assertThat(store.claim("notify-web", "nonce-1", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.CLAIMED);
    }
}
