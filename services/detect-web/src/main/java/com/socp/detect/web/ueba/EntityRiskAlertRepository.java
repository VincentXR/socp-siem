package com.socp.detect.web.ueba;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

interface EntityRiskAlertRepository extends JpaRepository<EntityRiskAlertEntity, String> {
    long countByEntityAndCreatedAtAfter(String entity, Instant cutoff);
}
