package com.socp.alert.domain;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

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
