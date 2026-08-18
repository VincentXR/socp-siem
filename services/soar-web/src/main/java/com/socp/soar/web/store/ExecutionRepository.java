package com.socp.soar.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRepository extends JpaRepository<ExecutionEntity, String> {

    List<ExecutionEntity> findTop200ByTenantIdOrderByTsDesc(String tenantId);
}
