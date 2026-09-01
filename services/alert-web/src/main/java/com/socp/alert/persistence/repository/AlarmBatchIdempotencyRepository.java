package com.socp.alert.persistence.repository;

import com.socp.alert.persistence.entity.AlarmBatchIdempotency;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

/** Tenant-bound idempotency records for batch alarm commands. */
public interface AlarmBatchIdempotencyRepository
        extends TenantScopedRepository<AlarmBatchIdempotency, String> {

    Optional<AlarmBatchIdempotency> findByTenantIdAndIdempotencyKey(
            String tenantId, String idempotencyKey);
}
