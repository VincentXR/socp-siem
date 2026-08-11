package com.socp.detect.model.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyzedRepository extends JpaRepository<AnalyzedEntity, Long> {
}
