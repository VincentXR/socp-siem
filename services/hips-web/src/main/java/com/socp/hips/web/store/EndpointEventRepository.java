package com.socp.hips.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EndpointEventRepository extends JpaRepository<EndpointEventEntity, String> {

    List<EndpointEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
