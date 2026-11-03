package db.migration.detect.journal;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Reduce compression CPU on durable result writes without rewriting receipts. */
public class V34__journal_result_compression extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 340001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported journal database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Existing values retain their compression and remain readable.
            // PostgreSQL builds must support LZ4; do not silently skip the policy.
            statement.execute("alter table t_detection_event alter column result_json set compression lz4");
        }
    }
}
