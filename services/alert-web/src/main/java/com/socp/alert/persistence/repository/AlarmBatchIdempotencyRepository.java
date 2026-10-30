package com.socp.alert.persistence.repository;

import com.socp.alert.persistence.entity.AlarmBatchIdempotency;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

/** Tenant-bound idempotency records for batch alarm commands. */
public interface AlarmBatchIdempotencyRepository
        extends TenantScopedRepository<AlarmBatchIdempotency, String>, AlarmBatchIdempotencyRepositoryCustom {

    Optional<AlarmBatchIdempotency> findByTenantIdAndIdempotencyKey(
            String tenantId, String idempotencyKey);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update AlarmBatchIdempotency b set b.responseJson = :response, b.updatedAt = :now
            where b.tenantId = :tenant and b.idempotencyKey = :key
            """)
    int complete(@org.springframework.data.repository.query.Param("tenant") String tenant,
                 @org.springframework.data.repository.query.Param("key") String key,
                 @org.springframework.data.repository.query.Param("response") String response,
                 @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
