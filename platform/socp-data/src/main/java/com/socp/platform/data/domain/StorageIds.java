package com.socp.platform.data.domain;

import java.util.UUID;

/** Time-local UUIDv7 storage keys; business identity and ordering remain separate. */
public final class StorageIds {
    private StorageIds() { }

    public static String next() {
        UUID random = UUID.randomUUID();
        // RFC 9562 section 5.7: 48 timestamp bits followed by 74 random bits.
        // No process-local counter/clock assumption is used for uniqueness.
        // These keys are not sequence numbers or authorization credentials.
        long timestamp = System.currentTimeMillis() & 0xffffffffffffL;
        return new UUID((timestamp << 16) | 0x7000L | (random.getMostSignificantBits() & 0xfffL),
                random.getLeastSignificantBits()).toString();
    }
}
