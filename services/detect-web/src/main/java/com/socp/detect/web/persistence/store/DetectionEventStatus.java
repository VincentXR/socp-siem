package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.entity.WatchlistEntity;/** Durable lifecycle of a canonical event accepted by Detection. */
public enum DetectionEventStatus {
    PENDING,
    COMPLETED,
    DEAD_LETTERED
}
