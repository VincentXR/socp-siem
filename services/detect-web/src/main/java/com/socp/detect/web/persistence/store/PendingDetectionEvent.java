package com.socp.detect.web.persistence.store;


import com.socp.rule.model.SecurityEvent;

/** Pending journal row retained with Kafka ownership metadata for recovery. */
public record PendingDetectionEvent(SecurityEvent event, Integer partition, Long offset) {
}
