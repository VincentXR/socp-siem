package db.migration.detect.watchlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Frozen portable backfill. Stop old watchlist writers before upgrading. */
public class V28__watchlist_value_counts extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 280001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("alter table t_watchlist add column value_count integer not null default 0");
        }
        ObjectMapper json = new ObjectMapper();
        long after = 0;
        while (true) {
            int count = 0;
            try (var select = connection.prepareStatement(
                    "select id, values_json, deleted from t_watchlist where id > ? order by id limit 100");
                 var update = connection.prepareStatement("update t_watchlist set value_count = ? where id = ?")) {
                select.setLong(1, after);
                select.setFetchSize(100);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        after = rows.getLong(1);
                        var values = json.readTree(rows.getString(2));
                        if (values == null || !values.isArray()) {
                            throw new IllegalStateException("Invalid watchlist JSON at row " + after);
                        }
                        var unique = new java.util.HashSet<String>();
                        for (var value : values) {
                            if (!value.isTextual()) throw new IllegalStateException("Invalid watchlist member at row " + after);
                            unique.add(value.textValue());
                        }
                        update.setInt(1, rows.getBoolean(3) ? 0 : unique.size());
                        update.setLong(2, after);
                        update.executeUpdate();
                        count++;
                    }
                }
            }
            if (count < 100) return;
        }
    }
}
