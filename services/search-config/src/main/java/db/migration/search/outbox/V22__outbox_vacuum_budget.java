package db.migration.search.outbox;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep queue maintenance thresholds independent of retained publication history. */
public class V22__outbox_vacuum_budget extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 220001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported outbox database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Claims and acknowledgements leave dead entries even in partial
            // indexes. A history-sized threshold delays their reclamation.
            // These are eligibility thresholds, not a bound on worker latency.
            statement.execute("alter table t_ingestion_outbox set ("
                    + "autovacuum_vacuum_scale_factor=0, autovacuum_vacuum_threshold=10000, "
                    + "autovacuum_analyze_scale_factor=0, autovacuum_analyze_threshold=10000, "
                    + "autovacuum_vacuum_cost_limit=1000, vacuum_index_cleanup=on)");
        }
    }
}
