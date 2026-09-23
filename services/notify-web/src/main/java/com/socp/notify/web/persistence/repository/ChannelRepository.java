package com.socp.notify.web.persistence.repository;


import com.socp.notify.web.persistence.entity.ChannelEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChannelRepository extends TenantScopedRepository<ChannelEntity, String> {
    java.util.List<ChannelEntity> findByTenantId(String tenantId);
    java.util.Optional<ChannelEntity> findByIdAndTenantId(String id, String tenantId);
    long countByTenantId(String tenantId);
    long countByTenantIdAndEnabledTrue(String tenantId);
    org.springframework.data.domain.Page<ChannelEntity> findByTenantIdOrderByNameAscIdAsc(
            String tenantId, org.springframework.data.domain.Pageable page);
    java.util.List<ChannelEntity> findByTenantIdAndEnabledTrueOrderByIdAsc(
            String tenantId, org.springframework.data.domain.Pageable page);
}
