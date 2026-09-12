package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Deletes expired bounded artifacts without touching run/event audit history. */
@Component
public class SoarArtifactRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarArtifactRetentionWorker.class);
    private final SoarArtifactRepository artifacts;
    private final SoarArtifactStore artifactStore;

    public SoarArtifactRetentionWorker(SoarArtifactRepository artifacts) {
        this(artifacts, (SoarArtifactStore) null);
    }

    /** Spring uses an ObjectProvider so preview deployments need no object store bean. */
    @org.springframework.beans.factory.annotation.Autowired
    public SoarArtifactRetentionWorker(SoarArtifactRepository artifacts,
                                       ObjectProvider<SoarArtifactStore> artifactStoreProvider) {
        this(artifacts, artifactStoreProvider.getIfAvailable());
    }

    SoarArtifactRetentionWorker(SoarArtifactRepository artifacts, SoarArtifactStore artifactStore) {
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
    }

    @Scheduled(fixedDelayString = "${socp.soar.artifact-retention-poll-ms:3600000}",
            initialDelayString = "${socp.soar.artifact-retention-initial-delay-ms:60000}")
    @TenantSystemJob
    public void tick() {
        Instant now = Instant.now();
        List<SoarArtifactEntity> expired = artifacts.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(now);
        if (expired == null || expired.isEmpty()) return;

        List<String> removable = new ArrayList<>(expired.size());
        for (SoarArtifactEntity artifact : expired) {
            // Inline rows may remain after a preview-to-production migration;
            // their database payload is already the source of truth.
            String storageRef = artifact.getStorageRef();
            if (storageRef != null && storageRef.startsWith("s3://")) {
                if (artifactStore == null) {
                    // Do not delete metadata while the remote adapter is
                    // unavailable: the row is the only durable handle with
                    // which a later retry can remove the object.
                    log.warn("SOAR artifact object deletion deferred because no object store is configured for {}",
                            artifact.getId());
                    break;
                }
                try {
                    artifactStore.delete(storageRef);
                } catch (RuntimeException failure) {
                    // Keep the metadata until the next retry; deleting the DB
                    // row first would orphan an object that retention cannot
                    // subsequently address.
                    log.warn("SOAR artifact object deletion deferred for {}", artifact.getId());
                    break;
                }
            }
            removable.add(artifact.getId());
        }
        int deleted = removable.isEmpty() ? 0 : artifacts.deleteByIds(removable, now);
        if (deleted > 0) log.info("SOAR artifact retention removed {} expired artifacts", deleted);
    }
}
