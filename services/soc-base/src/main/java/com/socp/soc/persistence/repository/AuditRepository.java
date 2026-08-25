package com.socp.soc.persistence.repository;

import com.socp.soc.persistence.entity.AuditEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 审计记录仓储（t_audit）。数据量小，聚合统计直接全查内存做。 */
@Repository
public interface AuditRepository extends JpaRepository<AuditEntity, Long> {

    boolean existsByEventId(String eventId);

    /** 按租户倒序取最近 N 条。 */
    List<AuditEntity> findTop500ByTenantIdOrderByTsDesc(String tenantId);

    List<AuditEntity> findByTenantId(String tenantId);

    long countByTenantId(String tenantId);
}
