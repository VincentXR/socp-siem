package com.socp.notify.web.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChannelRepository extends JpaRepository<ChannelEntity, String> {
    java.util.List<ChannelEntity> findByTenantId(String tenantId);
    java.util.Optional<ChannelEntity> findByIdAndTenantId(String id, String tenantId);
    long countByTenantId(String tenantId);
}
