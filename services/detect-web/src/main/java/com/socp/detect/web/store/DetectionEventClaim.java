package com.socp.detect.web.store;

/** Result of claiming an event id from the durable journal. */
public enum DetectionEventClaim {
    NEW,
    PENDING,
    COMPLETED,
    DEAD_LETTERED
}
