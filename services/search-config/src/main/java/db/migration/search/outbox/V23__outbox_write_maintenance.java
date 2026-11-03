package db.migration.search.outbox;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Reduce historical-index maintenance without changing publication receipts. */
public class V23__outbox_write_maintenance extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 230001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported outbox database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Tenant/event identity uses the unique composite index. Tenant
            // administration uses tenant/status/updated_at, and publication
            // uses the active due/key indexes. Neither legacy index is needed.
            statement.execute("drop index idx_ingestion_outbox_event");
            statement.execute("drop index idx_ingestion_outbox_tenant_due");
            // Vacuum still has to visit the retained identity indexes. A tiny
            // threshold repeats that work continuously under sustained input.
            // Statistics need a separate, less frequent budget than dead tuples.
            statement.execute("alter table t_ingestion_outbox set ("
                    + "autovacuum_vacuum_threshold=50000, autovacuum_analyze_threshold=100000)");
        }
    }
}
