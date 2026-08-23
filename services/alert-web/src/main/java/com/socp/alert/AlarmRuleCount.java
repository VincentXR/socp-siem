package com.socp.alert;

/** Database aggregation projection used by the alarm dashboard. */
public record AlarmRuleCount(String ruleId, long count) {
}
