package com.socp.alert.service;

import com.socp.alert.config.ClickHouseProperties;
import com.socp.alert.domain.Alarm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CkReporterTest {

    @Test
    void disabledClickHouseIsNotReportedAsDurableAcknowledgement() {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setEnabled(false);

        Alarm alarm = new Alarm();
        alarm.setId("alarm-1");

        assertFalse(new CkReporter(properties).reportAlarmAndAwait(alarm));
    }
}
