package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.entity.WatchlistEntity;/** Result of claiming an event id from the durable journal. */
public enum DetectionEventClaim {
    NEW,
    PENDING,
    COMPLETED,
    DEAD_LETTERED
}
