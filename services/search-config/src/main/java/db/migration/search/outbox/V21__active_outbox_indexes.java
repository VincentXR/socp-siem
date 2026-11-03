package db.migration.search.outbox;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Separate PostgreSQL queue access paths from retained publication history. */
public class V21__active_outbox_indexes extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 210001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return; // H2 retains the equivalent full indexes.
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported outbox database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Keep the predicate identical to queue queries. COALESCE avoids an
            // estimated-empty active population while table statistics still
            // describe only retained PUBLISHED rows. Status remains NOT NULL.
            // Build replacements before retiring the old access paths. Flyway's
            // transaction preserves all receipts and rolls back failed upgrades.
            statement.execute("create index idx_ingestion_outbox_due_covering_active on t_ingestion_outbox "
                    + "(status,next_attempt_at,sequence_no,id,tenant_id,routing_key) where coalesce(status,'') <> 'PUBLISHED'");
            statement.execute("create index idx_ingestion_outbox_state_key_order_active on t_ingestion_outbox "
                    + "(status,tenant_id,routing_key,sequence_no,id,next_attempt_at) where coalesce(status,'') <> 'PUBLISHED'");
            statement.execute("create unique index ux_ingestion_outbox_processing_key_active on t_ingestion_outbox "
                    + "(processing_key) where processing_key is not null");
            for (String index : java.util.List.of("idx_ingestion_outbox_due_covering",
                    "idx_ingestion_outbox_state_key_order", "ux_ingestion_outbox_processing_key")) {
                statement.execute("drop index " + index);
                statement.execute("alter index " + index + "_active rename to " + index);
            }
        }
    }
}
