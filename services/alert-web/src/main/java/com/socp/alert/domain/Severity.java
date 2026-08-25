package com.socp.alert.domain;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

/** 告警级别（与前端/原系统语义保持一致） */
public enum Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
