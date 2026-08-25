package com.socp.alert.domain;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import java.time.Instant;

/** Result returned after an operator explicitly requeues a terminal delivery. */
public record OutboxReplayResult(String id, String type, String tenantId, String status, Instant requeuedAt) {
}
