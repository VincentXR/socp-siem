package com.socp.search.config.store;

import org.springframework.data.jpa.repository.JpaRepository;

/** 日志源仓储。 */
public interface LogSourceRepository extends JpaRepository<LogSourceEntity, String> {
}
