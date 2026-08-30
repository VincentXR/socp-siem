package com.socp.alert.domain;


/** Database aggregation projection used by the alarm dashboard. */
public record AlarmRuleCount(String ruleId, long count) {
}
