package db.migration.detect.journal;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Reduce checkpoint compression cost while retaining the portable base64 format. */
public class V37__snapshot_state_compression extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 370001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported snapshot database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // Existing PGLZ values stay readable; no checkpoint rows are rewritten.
            statement.execute("alter table t_detection_state_snapshot alter column serialized_state set compression lz4");
        }
    }
}
