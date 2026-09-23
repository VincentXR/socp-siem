package com.socp.detect.web.persistence.repository;


import com.socp.detect.web.persistence.entity.WatchlistEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface WatchlistRepository extends TenantScopedRepository<WatchlistEntity, Long> {
    java.util.Optional<WatchlistEntity> findByIdAndTenantId(Long id, String tenantId);
    @Modifying
    @Transactional
    int deleteByTenantId(String tenantId);

    Optional<WatchlistEntity> findByTenantIdAndListName(String tenantId, String listName);
    long countByTenantId(String tenantId);

    interface Summary {
        String getName();
        int getSize();
        boolean getDeleted();
    }

    @Query("select w.listName as name, w.valueCount as size, w.deleted as deleted "
            + "from WatchlistEntity w where w.tenantId = :tenant order by w.listName")
    java.util.List<Summary> summaries(@Param("tenant") String tenant, Pageable pageable);

    @Query("select w.listName from WatchlistEntity w where w.tenantId = :tenant order by w.listName")
    java.util.List<String> findNames(@Param("tenant") String tenant, Pageable pageable);

    @Modifying
    @Transactional
    int deleteByTenantIdAndListName(String tenantId, String listName);

}
