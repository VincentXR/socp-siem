package com.socp.search.config.search;

import org.springframework.data.jpa.repository.JpaRepository;

/** 检索事件仓储。 */
public interface SearchEventRepository extends JpaRepository<SearchEventEntity, String> {
}
