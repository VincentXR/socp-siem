package db.migration.detect.journal;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep precise backlog statistics off terminal receipt access paths. */
public class V38__pending_journal_index extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 380001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        try (var statement = connection.createStatement()) {
            if ("H2".equals(product)) {
                statement.execute("create index idx_detection_event_pending_count "
                        + "on t_detection_event(tenant_id,delivery_topic,status)");
            } else if ("PostgreSQL".equals(product)) {
                statement.execute("create index idx_detection_event_pending_count "
                        + "on t_detection_event(tenant_id,delivery_topic) "
                        + "where coalesce(status,'')='PENDING'");
            } else {
                throw new IllegalStateException("Unsupported journal database: " + product);
            }
        }
    }
}
