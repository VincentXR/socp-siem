package db.migration.alert.delivery;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Bound dead queue versions independently of retained delivery history. */
public class V29__delivery_queue_vacuum_budget extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 290001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(product)) return;
        if (!"PostgreSQL".equals(product)) {
            throw new IllegalStateException("Unsupported delivery database: " + product);
        }
        try (var statement = connection.createStatement()) {
            // This changes maintenance eligibility; it neither rewrites nor
            // deletes receipts and does not promise immediate worker scheduling.
            statement.execute("alter table alarm_delivery set (autovacuum_vacuum_scale_factor=0,"
                    + "autovacuum_vacuum_threshold=10000,autovacuum_vacuum_cost_limit=1000,"
                    + "vacuum_index_cleanup=on)");
        }
    }
}
