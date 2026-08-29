package com.socp.soar.web.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoarPropertiesTest {

    @Test
    void runtimePropertiesExposeSafeDefaultsAndOverrides() {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();
        assertEquals("preview", properties.getMaturity());
        assertEquals("UTC", properties.getScheduleZone());

        properties.setSimulationEnabled(true);
        properties.setMaturity("production-ready");
        properties.setScheduleZone("Asia/Shanghai");

        assertTrue(properties.isSimulationEnabled());
        assertEquals("production-ready", properties.getMaturity());
        assertEquals("Asia/Shanghai", properties.getScheduleZone());
    }

    @Test
    void temporalPropertiesUseLocalDefaultsAndAcceptOverrides() {
        TemporalProperties properties = new TemporalProperties();
        assertTrue(properties.isEnabled());
        assertEquals("localhost:7233", properties.getTarget());
        assertEquals("default", properties.getNamespace());

        properties.setEnabled(false);
        properties.setTarget("temporal:7233");
        properties.setNamespace("socp");

        assertFalse(properties.isEnabled());
        assertEquals("temporal:7233", properties.getTarget());
        assertEquals("socp", properties.getNamespace());
    }
}
