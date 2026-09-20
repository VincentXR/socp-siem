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

    @Test
    void alarmTimestampIsFormattedInUtcRegardlessOfJvmZone() {
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            org.junit.jupiter.api.Assertions.assertEquals("2026-08-23 10:00:00.000",
                    CkReporter.CK_TS.format(java.time.Instant.parse("2026-08-23T10:00:00Z")));
        } finally {
            java.util.TimeZone.setDefault(previous);
        }
    }
}
