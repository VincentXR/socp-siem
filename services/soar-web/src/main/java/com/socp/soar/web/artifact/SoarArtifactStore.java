package com.socp.soar.web.artifact;

import java.util.Optional;

/**
 * External payload storage for SOAR evidence.  Run and node projections keep
 * only the immutable location and digest; implementations must never expose
 * credentials or provider response bodies to Temporal history.
 */
public interface SoarArtifactStore {

    StoredArtifact put(String tenantId, String runId, String artifactId,
                       String mediaType, byte[] content);

    Optional<byte[]> read(String storageRef);

    /** Deletion is idempotent; a missing remote object is not an error. */
    void delete(String storageRef);

    record StoredArtifact(String storageRef, long sizeBytes, String sha256) {
    }
}
