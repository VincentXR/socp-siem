package com.socp.alert;

/** Database aggregation projection used by the alarm dashboard. */
public record AlarmSeverityCount(Severity severity, long count) {
}
