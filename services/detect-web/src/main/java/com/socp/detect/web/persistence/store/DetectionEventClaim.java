package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;/** Result of claiming an event id from the durable journal. */
public enum DetectionEventClaim {
    NEW,
    PENDING,
    COMPLETED,
    DEAD_LETTERED
}
