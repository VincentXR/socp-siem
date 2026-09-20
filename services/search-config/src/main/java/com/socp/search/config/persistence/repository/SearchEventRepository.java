package com.socp.search.config.persistence.repository;

import com.socp.search.config.persistence.entity.SearchEventEntity;

import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** 检索事件仓储。 */
public interface SearchEventRepository extends TenantScopedRepository<SearchEventEntity, String> {
    long countByTenantId(String tenantId);
    List<SearchEventEntity> findByTenantIdOrderByTimestampDesc(String tenantId, Pageable pageable);
    List<SearchEventEntity> findByTenantIdAndEventIdIn(String tenantId, Collection<String> eventIds);

    @Modifying
    @Transactional
    @Query(value = "delete from t_search_event where id in ("
            + "select e.id from t_search_event e "
            + "where e.created_at < :cutoff "
            + "and not exists (select 1 from t_ingestion_outbox o "
            + "where o.tenant_id = e.tenant_id and o.event_id = e.event_id "
            + "and o.status in ('PENDING','PROCESSING','DEAD')) "
            + "order by e.created_at asc limit :batchSize)", nativeQuery = true)
    int deleteRetainedBatchBefore(@Param("cutoff") Instant cutoff,
                                  @Param("batchSize") int batchSize);
}
