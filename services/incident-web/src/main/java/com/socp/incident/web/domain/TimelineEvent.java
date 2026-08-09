package com.socp.incident.web.domain;

import java.time.Instant;

/** 案件时间线上的单个事件（用于调查还原攻击链）。alarmId 便于幂等校验与下钻。 */
public record TimelineEvent(Instant ts, String type, String message, String source, String alarmId) {
}
