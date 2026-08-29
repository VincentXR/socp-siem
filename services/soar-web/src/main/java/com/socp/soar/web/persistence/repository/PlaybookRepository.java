package com.socp.soar.web.persistence.repository;



import com.socp.soar.web.persistence.store.*;
import com.socp.soar.web.persistence.repository.*;
import com.socp.soar.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** 剧本仓储（H2/PG）。 */
public interface PlaybookRepository extends TenantScopedRepository<PlaybookEntity, String> {

    List<PlaybookEntity> findByTenantId(String tenantId);

    @Query("select distinct p.tenantId from PlaybookEntity p where p.enabled = true order by p.tenantId")
    List<String> findTenantIdsWithEnabledPlaybooks();

    Optional<PlaybookEntity> findByIdAndTenantId(String id, String tenantId);

    long countByTenantId(String tenantId);
}
