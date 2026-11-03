package com.socp.detect.web.persistence.entity;

import java.time.Instant;

/** Progress and concurrency receipt; checkpoint writers do not need the previous payload. */
public record DetectionCheckpointMetadata(String id, Instant snapshotTimestamp,
                                          String partitionOffsetsJson, long rowVersion) {
}
