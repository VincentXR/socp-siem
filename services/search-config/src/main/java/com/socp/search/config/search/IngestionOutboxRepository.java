package com.socp.search.config.search;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface IngestionOutboxRepository extends JpaRepository<IngestionOutboxEvent, String> {

    List<IngestionOutboxEvent> findTop200ByStatusOrderByCreatedAtAsc(String status);

    @Modifying
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PROCESSING', e.claimedAt = :now, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PENDING'")
    int claim(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PUBLISHED', e.publishedAt = :now, "
            + "e.updatedAt = :now where e.id = :id and e.status = 'PROCESSING'")
    int markPublished(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PENDING', e.claimedAt = null, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING'")
    int release(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PENDING', e.claimedAt = null, e.updatedAt = :now "
            + "where e.status = 'PROCESSING' and e.claimedAt < :cutoff")
    int recoverStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
