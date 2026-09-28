package com.socp.search.config.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.search.config.persistence.entity.IngestParseFailureEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

public interface IngestParseFailureRepository
        extends TenantScopedRepository<IngestParseFailureEntity, String> {
    Optional<IngestParseFailureEntity> findByIdAndTenantId(String id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from IngestParseFailureEntity f where f.id = :id and f.tenantId = :tenantId")
    Optional<IngestParseFailureEntity> findForReplay(
            @Param("id") String id, @Param("tenantId") String tenantId);
    Optional<IngestParseFailureEntity> findByTenantIdAndFailureKey(String tenantId, String failureKey);
    long countByTenantId(String tenantId);
    Page<IngestParseFailureEntity> findByTenantIdOrderByReceivedAtDescIdAsc(
            String tenantId, Pageable pageable);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "with candidates as (select id from t_ingest_parse_failure "
            + "where received_at < :cutoff order by received_at, id "
            + "limit :batchSize for update skip locked) "
            + "delete from t_ingest_parse_failure f using candidates c where f.id=c.id",
            nativeQuery = true)
    int deleteRetainedBatchBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
