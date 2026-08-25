package com.socp.alert.domain;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

/** Database aggregation projection used by the alarm dashboard. */
public record AlarmRiskLevelCount(String level, long count) {
}
