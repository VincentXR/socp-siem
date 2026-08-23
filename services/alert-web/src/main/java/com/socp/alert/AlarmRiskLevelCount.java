package com.socp.alert;

/** Database aggregation projection used by the alarm dashboard. */
public record AlarmRiskLevelCount(String level, long count) {
}
