package com.socp.hips.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

/** Deletes only unreferenced history; the receipt remains the request replay authority. */
@Service
public class EndpointHistoryRetentionStore {
    private final JdbcTemplate jdbc;

    public EndpointHistoryRetentionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 5)
    public int prune(Instant cutoff, int limit) {
        requireSystem();
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("History cleanup batch must be within 1..1000");
        // Same lock order as collection. Yield to active admission instead of waiting for it.
        if (jdbc.queryForList("select id from t_endpoint_forwarding_admission where id=1 for update skip locked", Integer.class).isEmpty())
            return 0;
        var ids = jdbc.query("""
                select e.event_id from t_endpoint_event e
                where e.received_at<? and not exists (
                    select 1 from t_endpoint_forwarding f where f.event_id=e.event_id)
                order by e.received_at,e.event_id limit ? for update skip locked
                """, (rs, row) -> rs.getString(1), Timestamp.from(cutoff), limit);
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.update("delete from t_endpoint_event where event_id in (" + placeholders + ")"
                + " and not exists (select 1 from t_endpoint_forwarding f where f.event_id=t_endpoint_event.event_id)", ids.toArray());
    }

    @Transactional(readOnly = true, timeout = 5)
    public Optional<Instant> oldestEligible(Instant cutoff) {
        requireSystem();
        return jdbc.query("""
                select e.received_at from t_endpoint_event e
                where e.received_at<? and not exists (
                    select 1 from t_endpoint_forwarding f where f.event_id=e.event_id)
                order by e.received_at,e.event_id limit 1
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), Timestamp.from(cutoff)).stream().findFirst();
    }

    private static void requireSystem() {
        if (!TenantContext.isSystemScope()) throw new IllegalStateException("History retention requires explicit system scope");
    }
}
