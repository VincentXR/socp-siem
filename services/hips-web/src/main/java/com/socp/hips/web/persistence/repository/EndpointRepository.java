package com.socp.hips.web.persistence.repository;



import com.socp.hips.web.persistence.store.*;
import com.socp.hips.web.persistence.repository.*;
import com.socp.hips.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface EndpointRepository extends JpaRepository<EndpointEntity, String> {
    List<EndpointEntity> findByTenantId(String tenantId);
    Optional<EndpointEntity> findByTenantIdAndEndpointId(String tenantId, String endpointId);
}
