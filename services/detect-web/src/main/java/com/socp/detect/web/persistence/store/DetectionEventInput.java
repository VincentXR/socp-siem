package com.socp.detect.web.persistence.store;

import com.socp.rule.model.SecurityEvent;

/** Validated delivery position retained by a bounded journal claim transaction. */
public record DetectionEventInput(SecurityEvent event, String topic, int partition, long offset,
                                  String routingKey) { }
