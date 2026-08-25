package com.socp.hips.web.persistence.repository;



import com.socp.hips.web.persistence.store.*;
import com.socp.hips.web.persistence.repository.*;
import com.socp.hips.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EndpointEventRepository extends JpaRepository<EndpointEventEntity, String> {

    List<EndpointEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
