package com.socp.alert.api.response;

import com.socp.alert.domain.AlarmEvidenceView;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
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
