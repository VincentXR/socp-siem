package com.socp.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Outbox 出站事件仓库：查询待发布事件（按时间升序，保证发布顺序与创建一致）。
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);
}
