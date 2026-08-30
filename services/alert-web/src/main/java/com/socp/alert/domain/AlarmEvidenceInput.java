package com.socp.alert.domain;


import java.time.Instant;
import java.util.Map;

/** Evidence snapshot sent by the detection engine when an alert is created. */
public record AlarmEvidenceInput(
        String eventId,
        Instant timestamp,
        String source,
        String host,
        String severity,
        String raw,
        Map<String, String> fields
) {
}
