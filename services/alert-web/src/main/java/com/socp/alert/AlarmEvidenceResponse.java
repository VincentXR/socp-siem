package com.socp.alert;

import java.util.List;

/** Evidence response used by the alert investigation drawer. */
public record AlarmEvidenceResponse(
        String alarmId,
        int total,
        boolean complete,
        String query,
        List<AlarmEvidenceView> items
) {
}
