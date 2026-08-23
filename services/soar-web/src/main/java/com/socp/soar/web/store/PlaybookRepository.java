package com.socp.soar.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 剧本仓储（H2/PG）。 */
public interface PlaybookRepository extends JpaRepository<PlaybookEntity, String> {

    List<PlaybookEntity> findByTenantId(String tenantId);

    Optional<PlaybookEntity> findByIdAndTenantId(String id, String tenantId);

    long countByTenantId(String tenantId);
}
