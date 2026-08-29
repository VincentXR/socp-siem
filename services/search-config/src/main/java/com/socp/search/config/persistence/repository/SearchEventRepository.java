package com.socp.search.config.persistence.repository;

import com.socp.search.config.persistence.entity.SearchEventEntity;

import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;

/** 检索事件仓储。 */
public interface SearchEventRepository extends TenantScopedRepository<SearchEventEntity, String> {
    long countByTenantId(String tenantId);
    List<SearchEventEntity> findTop20000ByTenantIdOrderByTimestampDesc(String tenantId);
}
