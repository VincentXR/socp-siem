package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.rule.model.SecurityEvent;

/** Pending journal row retained with Kafka ownership metadata for recovery. */
public record PendingDetectionEvent(SecurityEvent event, Integer partition, Long offset) {
}
