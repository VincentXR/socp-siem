package com.socp.alert.domain;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

/**
 * Normalized, tenant-independent alarm list criteria. Tenant ownership is supplied
 * separately by the service boundary so every repository query remains tenant-scoped.
 */
public record AlarmQuery(
        Severity severity,
        String rule,
        String status,
        String text,
        SortField sort,
        boolean ascending) {

    public enum SortField {
        OCCURRED_AT,
        ALERT_CREATED_AT,
        SEVERITY,
        RULE_NAME,
        ENTITY,
        STATUS,
        RISK_SCORE
    }
}
