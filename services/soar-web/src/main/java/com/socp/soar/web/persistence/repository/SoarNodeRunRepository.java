package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoarNodeRunRepository extends TenantScopedRepository<SoarNodeRunEntity, String> {
    List<SoarNodeRunEntity> findByTenantIdAndRunIdOrderByUpdatedAtAsc(String tenantId, String runId);
    Page<SoarNodeRunEntity> findByTenantIdAndRunIdOrderByUpdatedAtAsc(String tenantId, String runId,
                                                                       Pageable pageable);
    Optional<SoarNodeRunEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from SoarNodeRunEntity n where n.tenantId = :tenantId and n.id = :id")
    Optional<SoarNodeRunEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                              @Param("id") String id);
    Optional<SoarNodeRunEntity> findByTenantIdAndRunIdAndNodeIdAndIterationPath(
            String tenantId, String runId, String nodeId, String iterationPath);

    /** System-scope retention helper: node ids owned by the given runs. */
    @Query("select n.id from SoarNodeRunEntity n where n.runId in :runIds")
    List<String> findIdsByRunIdIn(@Param("runIds") Collection<String> runIds);

    /** System-scope retention purge; call only from SoarRunRetentionWorker. */
    @Modifying
    @Query("delete from SoarNodeRunEntity n where n.runId in :runIds")
    int deleteByRunIdIn(@Param("runIds") Collection<String> runIds);
}
