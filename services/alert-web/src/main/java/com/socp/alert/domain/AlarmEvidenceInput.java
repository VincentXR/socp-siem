package com.socp.alert.domain;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

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
