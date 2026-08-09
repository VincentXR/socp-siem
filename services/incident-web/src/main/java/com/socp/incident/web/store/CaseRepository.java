package com.socp.incident.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 案件仓储：按实体查询进行中案件（用于告警归并）。 */
public interface CaseRepository extends JpaRepository<CaseEntity, String> {

    List<CaseEntity> findByEntityAndStatusIn(String entity, List<String> statuses);
}
