package com.socp.soar.web.persistence.repository;



import com.socp.soar.web.persistence.store.*;
import com.socp.soar.web.persistence.repository.*;
import com.socp.soar.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRepository extends JpaRepository<ExecutionEntity, String> {

    List<ExecutionEntity> findTop200ByTenantIdOrderByTsDesc(String tenantId);
}
