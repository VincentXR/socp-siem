package com.socp.soc.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaAuditPropertiesTest {

    @Test
    void exposesAuditKafkaDefaults() {
        KafkaAuditProperties properties = new KafkaAuditProperties();

        assertEquals("localhost:9092", properties.getBootstrap());
        assertEquals("socp-audit", properties.getAuditTopic());
        assertTrue(properties.isAuditEnabled());
    }
}
