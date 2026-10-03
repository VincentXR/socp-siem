package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import static com.socp.soar.web.service.SoarService.error;

/** Tenant-scoped record lookup and artifact expiry authorization. */
final class SoarRecords {
    private final SoarPlaybookRepository playbooks;
    private final PlaybookVersionRepository versions;
    private final SoarRunRepository runs;
    private SoarArtifactRepository artifacts;
    SoarRecords(SoarPlaybookRepository playbooks, PlaybookVersionRepository versions, SoarRunRepository runs) {
        this.playbooks = playbooks; this.versions = versions; this.runs = runs;
    }
    void setArtifacts(SoarArtifactRepository artifacts) { this.artifacts = artifacts; }

    SoarPlaybookEntity playbook(String id) {
        return playbooks.findByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND", "playbook not found"));
    }

    PlaybookVersionEntity version(String playbookId, int versionNo) {
        playbook(playbookId);
        return versions.findByTenantIdAndPlaybookIdAndVersionNo(TenantContext.require(), playbookId, versionNo)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND", "version not found"));
    }

    SoarRunEntity run(String id) {
        return runs.findByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
    }

    SoarArtifactEntity artifact(String id) {
        if (artifacts == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                    "artifact storage adapter is not configured");
        }
        SoarArtifactEntity value = artifacts.findByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_ARTIFACT_NOT_FOUND", "artifact not found"));
        if (value.getExpiresAt() != null && value.getExpiresAt().isBefore(Instant.now())) {
            throw error(HttpStatus.GONE, "SOAR_ARTIFACT_EXPIRED", "artifact has expired");
        }
        return value;
    }
}
