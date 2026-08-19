package com.socp.detect.web.store;

/** Durable lifecycle of a canonical event accepted by Detection. */
public enum DetectionEventStatus {
    PENDING,
    COMPLETED,
    DEAD_LETTERED
}
