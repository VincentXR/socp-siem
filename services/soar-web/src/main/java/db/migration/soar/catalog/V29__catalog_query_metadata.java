package db.migration.soar.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Frozen, keyset-batched JSON metadata backfill for H2 and PostgreSQL. */
public class V29__catalog_query_metadata extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override public Integer getChecksum() { return 290001; }
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("alter table t_soar_playbook add column tag_tokens text");
            statement.execute("alter table t_soar_playbook_version add column high_risk_action_count integer not null default 0");
            statement.execute("alter table t_soar_playbook_version add column action_count integer not null default 0");
            statement.execute("create index idx_soar_version_catalog on t_soar_playbook_version (tenant_id, playbook_id, status, version_no)");
        }
        backfill(context, false);
        backfill(context, true);
    }
    private void backfill(Context context, boolean versions) throws Exception {
        String table = versions ? "t_soar_playbook_version" : "t_soar_playbook";
        String source = versions ? "risk_summary_json" : "tags_json";
        String cursor = "";
        while (true) {
            int count = 0;
            try (var select = context.getConnection().prepareStatement(
                    "select id," + source + " from " + table + " where id > ? order by id limit 250");
                 var update = context.getConnection().prepareStatement(versions
                    ? "update t_soar_playbook_version set high_risk_action_count=?, action_count=? where id=?"
                    : "update t_soar_playbook set tag_tokens=? where id=?")) {
                select.setString(1, cursor);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        cursor = rows.getString(1);
                        if (versions) {
                            int[] counts = riskCounts(rows.getString(2));
                            update.setInt(1, counts[0]); update.setInt(2, counts[1]); update.setString(3, cursor);
                        } else { update.setString(1, tagTokens(rows.getString(2))); update.setString(2, cursor); }
                        update.addBatch(); count++;
                    }
                }
                update.executeBatch();
            }
            if (count < 250) return;
        }
    }
    public static String tagToken(String tag) {
        return "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tag.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) + "|";
    }
    public static String tagTokens(String json) {
        try {
            var root = JSON.readTree(json == null ? "[]" : json);
            TreeSet<String> tokens = new TreeSet<>();
            if (root != null && root.isArray()) root.forEach(value -> tokens.add(tagToken(value.asText())));
            return String.join("", tokens);
        } catch (Exception ignored) { return ""; }
    }
    public static int[] riskCounts(String json) {
        try {
            var root = JSON.readTree(json == null ? "{}" : json);
            return root == null ? new int[] { 0, 0 } : new int[] {
                    Math.max(0, root.path("highRiskActionCount").asInt(0)),
                    Math.max(0, root.path("actionCount").asInt(0)) };
        } catch (Exception ignored) { return new int[] { 0, 0 }; }
    }
}
