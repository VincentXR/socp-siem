package com.socp.search.config.persistence.repository;



import com.socp.search.config.persistence.store.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 日志源仓储。 */
public interface LogSourceRepository extends JpaRepository<LogSourceEntity, String> {
    List<LogSourceEntity> findByTenantId(String tenantId);
    Optional<LogSourceEntity> findByTenantIdAndSourceId(String tenantId, String sourceId);
}
