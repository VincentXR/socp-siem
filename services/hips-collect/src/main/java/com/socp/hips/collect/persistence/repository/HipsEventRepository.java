package com.socp.hips.collect.persistence.repository;



import com.socp.hips.collect.persistence.store.*;
import com.socp.hips.collect.persistence.repository.*;
import com.socp.hips.collect.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HipsEventRepository extends JpaRepository<HipsEventEntity, String> {
    List<HipsEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
