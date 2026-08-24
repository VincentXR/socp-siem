package com.socp.hips.collect.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HipsEventRepository extends JpaRepository<HipsEventEntity, String> {
    List<HipsEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
