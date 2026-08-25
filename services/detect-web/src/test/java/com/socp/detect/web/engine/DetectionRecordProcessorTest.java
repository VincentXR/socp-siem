package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DetectionRecordProcessorTest {

    @Test
    void parsesCanonicalFieldsWithoutUncheckedMaps() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.NormalizedDetectionRecord record = processor.parse(
                "ignored", """
                        {"eventId":"evt-1","timestamp":"2026-08-23T00:00:00Z",
                         "source":"auth","host":"web-1","severity":"high","msg":"login failed",
                         "fields":{"src_ip":"198.51.100.10","attempts":3}}
                        """);

        assertEquals("evt-1", record.event().id());
        assertEquals("HIGH", record.event().severity().name());
        assertEquals("3", record.event().fields().get("attempts"));
        assertTrue(record.routingKey().contains("198.51.100.10"));
    }

    @Test
    void rejectsNonObjectFieldsAsTerminalPayload() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.MalformedDetectionRecordException error = assertThrows(
                DetectionRecordProcessor.MalformedDetectionRecordException.class,
                () -> processor.parse("key", "{\"eventId\":\"evt-bad\",\"fields\":[]}"));

        assertEquals("evt-bad", error.eventId());
    }
}
