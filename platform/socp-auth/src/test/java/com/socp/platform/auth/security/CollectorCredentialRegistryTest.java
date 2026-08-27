package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorCredentialRegistryTest {

    @Test
    void authenticatesConfiguredSecretWithoutExposingIt() {
        CollectorCredentialRegistry registry =
                new CollectorCredentialRegistry("vector-a|tenant-a|secret-a;falco-b|tenant-b|secret-b");

        var identity = registry.authenticate("secret-b");

        assertTrue(identity.isPresent());
        assertEquals("falco-b", identity.get().collectorId());
        assertEquals("tenant-b", identity.get().tenantId());
        assertTrue(registry.authenticate("wrong").isEmpty());
    }

    @Test
    void rejectsMalformedOrDuplicateConfiguration() {
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("broken-entry"));
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("a|tenant-a|one;a|tenant-a|two"));
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("a|tenant-a|same;b|tenant-b|same"));
    }
}
