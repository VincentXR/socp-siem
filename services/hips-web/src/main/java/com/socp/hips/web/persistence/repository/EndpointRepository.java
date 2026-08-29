package com.socp.hips.web.persistence.repository;



import com.socp.hips.web.persistence.store.*;
import com.socp.hips.web.persistence.repository.*;
import com.socp.hips.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface EndpointRepository extends TenantScopedRepository<EndpointEntity, String> {
    List<EndpointEntity> findByTenantId(String tenantId);
    Optional<EndpointEntity> findByStorageIdAndTenantId(String storageId, String tenantId);
    Optional<EndpointEntity> findByTenantIdAndEndpointId(String tenantId, String endpointId);
}
