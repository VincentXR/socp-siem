package com.socp.search.config.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigCachePropertiesTest {

    @Test
    void zeroCannotDisableCrossReplicaConfigurationRefresh() {
        ConfigCacheProperties properties = new ConfigCacheProperties();
        assertEquals(60_000L, properties.getTtlMs());
        assertThrows(IllegalArgumentException.class, () -> properties.setTtlMs(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setTtlMs(-1));
        properties.setTtlMs(1);
        assertEquals(1L, properties.getTtlMs());
    }
}
