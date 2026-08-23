package com.socp.search.config.search;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 检索事件仓储。 */
public interface SearchEventRepository extends JpaRepository<SearchEventEntity, String> {
    long countByTenantId(String tenantId);
    List<SearchEventEntity> findTop20000ByTenantIdOrderByTimestampDesc(String tenantId);
}
