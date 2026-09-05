package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Expires pre-dispatch approval gates even when no operator opens the approval
 * screen.  Temporal remains authoritative for an attached workflow; this
 * janitor only closes the durable approval/run projection and is idempotent.
 */
@Component
public class SoarV2ApprovalExpiryWorker {
    private final SoarApprovalRepository approvals;
    private final SoarV2Service soar;

    public SoarV2ApprovalExpiryWorker(SoarApprovalRepository approvals, SoarV2Service soar) {
        this.approvals = approvals;
        this.soar = soar;
    }

    @Scheduled(fixedDelayString = "${socp.soar.v2.approval-expiry-poll-ms:30000}",
            initialDelayString = "${socp.soar.v2.approval-expiry-initial-delay-ms:30000}")
    @TenantSystemJob
    public void tick() {
        Instant now = Instant.now();
        List<SoarApprovalEntity> expired = approvals
                .findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc("PENDING", now);
        for (SoarApprovalEntity approval : expired) {
            String tenant = approval.getTenantId();
            if (tenant == null || tenant.isBlank()) continue;
            TenantContext.runWith(tenant, () -> soar.expireApproval(approval.getId(), now));
        }
    }
}
