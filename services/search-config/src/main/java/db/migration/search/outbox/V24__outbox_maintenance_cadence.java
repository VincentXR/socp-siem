package db.migration.search.outbox;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Reduce repeated maintenance scans of retained identity indexes. */
public class V24__outbox_maintenance_cadence extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 240001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported outbox database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Every claim and settlement creates a dead version. Small thresholds
            // repeatedly scan historical identity indexes during sustained input.
            // Keep cleanup enabled, with fixed eligibility independent of history.
            statement.execute("alter table t_ingestion_outbox set ("
                    + "autovacuum_vacuum_threshold=200000, autovacuum_analyze_threshold=250000)");
        }
    }
}
