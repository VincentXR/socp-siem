package com.socp.soar.web.service;

import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Deletes expired bounded artifacts without touching run/event audit history. */
@Component
public class SoarArtifactRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(SoarArtifactRetentionWorker.class);
    private final SoarArtifactRepository artifacts;

    public SoarArtifactRetentionWorker(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.artifact-retention-poll-ms:3600000}",
            initialDelayString = "${socp.soar.v2.artifact-retention-initial-delay-ms:60000}")
    @TenantSystemJob
    public void tick() {
        int deleted = artifacts.deleteExpired(Instant.now());
        if (deleted > 0) log.info("SOAR artifact retention removed {} expired artifacts", deleted);
    }
}
