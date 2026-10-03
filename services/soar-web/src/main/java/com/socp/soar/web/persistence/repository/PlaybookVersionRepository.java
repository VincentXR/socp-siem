package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlaybookVersionRepository extends TenantScopedRepository<PlaybookVersionEntity, String> {
    Optional<PlaybookVersionEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PlaybookVersionEntity v where v.tenantId = :tenantId and v.id = :id")
    Optional<PlaybookVersionEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                                  @Param("id") String id);
    List<PlaybookVersionEntity> findByTenantIdAndPlaybookIdOrderByVersionNoDesc(String tenantId, String playbookId);
    Optional<PlaybookVersionEntity> findByTenantIdAndPlaybookIdAndVersionNo(String tenantId, String playbookId,
                                                                              Integer versionNo);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PlaybookVersionEntity v where v.tenantId = :tenantId "
            + "and v.playbookId = :playbookId and v.versionNo = :versionNo")
    Optional<PlaybookVersionEntity> findByTenantIdAndPlaybookIdAndVersionNoForUpdate(
            @Param("tenantId") String tenantId, @Param("playbookId") String playbookId,
            @Param("versionNo") Integer versionNo);
    Optional<PlaybookVersionEntity> findFirstByTenantIdAndPlaybookIdAndStatusOrderByVersionNoDesc(
            String tenantId, String playbookId, String status);
    /** Metadata-only query bounded by the current catalogue page, independent of retained revisions. */
    @Query("select v.playbookId as playbookId, max(v.versionNo) as versionNo "
            + "from PlaybookVersionEntity v where v.tenantId = :tenantId "
            + "and v.playbookId in :playbookIds and v.status = 'DRAFT' group by v.playbookId")
    List<DraftMetadata> findDraftMetadata(@Param("tenantId") String tenantId,
                                          @Param("playbookIds") java.util.Collection<String> playbookIds);

    interface DraftMetadata {
        String getPlaybookId();
        Integer getVersionNo();
    }

    int countByTenantIdAndPlaybookId(String tenantId, String playbookId);
}
