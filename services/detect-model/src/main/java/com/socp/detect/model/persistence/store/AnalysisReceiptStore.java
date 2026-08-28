package com.socp.detect.model.persistence.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.Locale;

/**
 * Durable claim/complete receipt for a source alarm analysis.
 *
 * <p>The receipt is written in the same transaction as the analysis result.
 * A redelivered Kafka record therefore either observes the committed receipt
 * and becomes a no-op, or observes the rolled-back transaction and can be
 * evaluated normally.</p>
 */
@Component
public class AnalysisReceiptStore {

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    /** Compatibility constructor used by pure unit tests without a database. */
    public AnalysisReceiptStore() {
        this.jdbc = null;
        this.postgres = false;
    }

    @Autowired
    public AnalysisReceiptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = isPostgres(jdbc);
    }

    public boolean claim(String tenantId, String sourceAlarmId, String analyzerVersion) {
        if (jdbc == null) return true;
        String sql = postgres ? """
                INSERT INTO t_analysis_receipt
                    (tenant_id, source_alarm_id, analyzer_version, status, claimed_at)
                VALUES (?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, source_alarm_id, analyzer_version) DO NOTHING
                """ : """
                MERGE INTO t_analysis_receipt AS target
                USING (VALUES (?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)) AS incoming
                    (tenant_id, source_alarm_id, analyzer_version, status, claimed_at)
                ON target.tenant_id = incoming.tenant_id
                   AND target.source_alarm_id = incoming.source_alarm_id
                   AND target.analyzer_version = incoming.analyzer_version
                WHEN NOT MATCHED THEN INSERT
                    (tenant_id, source_alarm_id, analyzer_version, status, claimed_at)
                VALUES (incoming.tenant_id, incoming.source_alarm_id,
                        incoming.analyzer_version, incoming.status, incoming.claimed_at)
                """;
        int inserted = jdbc.update(sql, tenantId, sourceAlarmId, analyzerVersion);
        return inserted == 1;
    }

    public void complete(String tenantId, String sourceAlarmId, String analyzerVersion, int resultCount) {
        if (jdbc == null) return;
        int updated = jdbc.update("""
                UPDATE t_analysis_receipt
                   SET status = 'COMPLETED', completed_at = ?, result_count = ?
                 WHERE tenant_id = ? AND source_alarm_id = ? AND analyzer_version = ?
                """, Timestamp.from(Instant.now()), resultCount, tenantId, sourceAlarmId, analyzerVersion);
        if (updated != 1) {
            throw new IllegalStateException("analysis receipt was not claimed before completion");
        }
    }

    private static boolean isPostgres(JdbcTemplate jdbc) {
        if (jdbc == null || jdbc.getDataSource() == null) return false;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            return connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception ignored) {
            // H2 and unit-test data sources use the portable MERGE branch.
            return false;
        }
    }
}
