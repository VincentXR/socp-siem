package com.socp.detect.model.kafka;

import com.socp.detect.model.service.AnalyzeService;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmConsumerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void restoresTenantOnlyForTheAnalysisCall() {
        AnalyzeService service = mock(AnalyzeService.class);
        when(service.analyze(anyMap())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return java.util.Map.of();
        });
        AlarmConsumer consumer = new AlarmConsumer(service);

        consumer.processRecord("alarm-1",
                "{\"tenantId\":\"tenant-a\",\"severity\":\"HIGH\"}");

        verify(service).analyze(anyMap());
        assertNull(TenantContext.get());
    }

    @Test
    void rejectsInvalidTenantBeforeCallingAnalysis() {
        AnalyzeService service = mock(AnalyzeService.class);
        AlarmConsumer consumer = new AlarmConsumer(service);

        assertThrows(AlarmConsumer.InvalidAlarmEventException.class,
                () -> consumer.processRecord("alarm-1",
                        "{\"tenantId\":\"../other\",\"severity\":\"HIGH\"}"));

        verify(service, never()).analyze(anyMap());
    }
}
