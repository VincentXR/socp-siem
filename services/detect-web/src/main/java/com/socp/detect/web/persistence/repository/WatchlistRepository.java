package com.socp.detect.web.persistence.repository;


import com.socp.detect.web.persistence.entity.WatchlistEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface WatchlistRepository extends TenantScopedRepository<WatchlistEntity, Long> {
    java.util.Optional<WatchlistEntity> findByIdAndTenantId(Long id, String tenantId);
    @Modifying
    @Transactional
    int deleteByTenantId(String tenantId);

    Optional<WatchlistEntity> findByTenantIdAndListName(String tenantId, String listName);

}
