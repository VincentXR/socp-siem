package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;/** Durable lifecycle of a canonical event accepted by Detection. */
public enum DetectionEventStatus {
    PENDING,
    COMPLETED,
    DEAD_LETTERED
}
