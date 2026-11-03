package db.migration.search.outbox;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep due-order coverage without offering misleading trailing key predicates. */
public class V25__outbox_due_index_payload_columns extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 250001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported outbox database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Tenant/key are payload for due discovery, not seek keys after
            // next_attempt_at/sequence/id. Treating them as trailing keys can
            // make per-key fences scan the due range instead of the key index.
            statement.execute("create index idx_ingestion_outbox_due_order_payload "
                    + "on t_ingestion_outbox(status,next_attempt_at,sequence_no,id) "
                    + "include(tenant_id,routing_key) where coalesce(status,'') <> 'PUBLISHED'");
            statement.execute("drop index idx_ingestion_outbox_due_covering");
            statement.execute("alter index idx_ingestion_outbox_due_order_payload "
                    + "rename to idx_ingestion_outbox_due_covering");
        }
    }
}
