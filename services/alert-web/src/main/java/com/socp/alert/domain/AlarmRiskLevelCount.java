package com.socp.alert.domain;


/** Database aggregation projection used by the alarm dashboard. */
public record AlarmRiskLevelCount(String level, long count) {
}
