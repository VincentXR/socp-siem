package com.socp.alert.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHousePropertiesTest {

    @Test
    void exposesDefaultsAndSupportsOverrides() {
        ClickHouseProperties properties = new ClickHouseProperties();
        assertEquals("http://localhost:8123", properties.getUrl());
        assertEquals("default", properties.getUser());
        assertTrue(properties.isEnabled());

        properties.setUrl("http://clickhouse:8123");
        properties.setUser("socp");
        properties.setPassword("secret");
        properties.setEnabled(false);

        assertEquals("http://clickhouse:8123", properties.getUrl());
        assertEquals("socp", properties.getUser());
        assertEquals("secret", properties.getPassword());
        assertFalse(properties.isEnabled());
    }
}
