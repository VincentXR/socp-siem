package com.socp.detect.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface WatchlistRepository extends JpaRepository<WatchlistEntity, Long> {

    Optional<WatchlistEntity> findByTenantIdAndListName(String tenantId, String listName);

    List<WatchlistEntity> findByTenantId(String tenantId);
}
