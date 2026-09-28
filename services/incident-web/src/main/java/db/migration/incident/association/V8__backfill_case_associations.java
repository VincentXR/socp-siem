package db.migration.incident.association;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * One-time portable backfill for associations that predate the normalized
 * tables. V7 is already published, so its checksum stays untouched.
 */
public class V8__backfill_case_associations extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    @Override
    public Integer getChecksum() {
        return 80001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        String alarmInsert = postgres
                ? "insert into t_alarm_case_link (id, tenant_id, alarm_id, case_id, created_at) "
                    + "values (?, ?, ?, ?, ?) on conflict (tenant_id, alarm_id) do nothing"
                : "insert into t_alarm_case_link (id, tenant_id, alarm_id, case_id, created_at) "
                    + "select ?, ?, ?, ?, ? where not exists "
                    + "(select 1 from t_alarm_case_link where tenant_id=? and alarm_id=?)";
        String ruleInsert = postgres
                ? "insert into t_case_rule_link (id, tenant_id, case_id, rule_id, created_at) "
                    + "values (?, ?, ?, ?, ?) on conflict (tenant_id, case_id, rule_id) do nothing"
                : "insert into t_case_rule_link (id, tenant_id, case_id, rule_id, created_at) "
                    + "select ?, ?, ?, ?, ? where not exists "
                    + "(select 1 from t_case_rule_link where tenant_id=? and case_id=? and rule_id=?)";

        try (PreparedStatement select = connection.prepareStatement(
                "select id, tenant_id, alarm_ids, rule_ids from t_case order by id");
             PreparedStatement insertAlarm = connection.prepareStatement(alarmInsert);
             PreparedStatement insertRule = connection.prepareStatement(ruleInsert);
             PreparedStatement compact = connection.prepareStatement("update t_case set alarm_ids='[]', rule_ids='[]' "
                     + "where id=? and tenant_id=? "
                     + "and ((alarm_ids=?) or (alarm_ids is null and ? is null)) "
                     + "and ((rule_ids=?) or (rule_ids is null and ? is null))")) {
            select.setFetchSize(250);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String caseId = rows.getString(1);
                    String tenant = rows.getString(2);
                    String alarmJson = rows.getString(3);
                    String ruleJson = rows.getString(4);
                    for (String alarmId : values(alarmJson)) {
                        bindAlarm(insertAlarm, postgres, tenant, caseId, alarmId);
                        insertAlarm.executeUpdate();
                    }
                    for (String ruleId : values(ruleJson)) {
                        bindRule(insertRule, postgres, tenant, caseId, ruleId);
                        insertRule.executeUpdate();
                    }
                    // Do not clear a row changed by an old replica during a
                    // rolling upgrade. Runtime merged reads keep that row safe.
                    compact.setString(1, caseId);
                    compact.setString(2, tenant);
                    compact.setString(3, alarmJson);
                    compact.setString(4, alarmJson);
                    compact.setString(5, ruleJson);
                    compact.setString(6, ruleJson);
                    compact.executeUpdate();
                }
            }
        }
    }

    private static List<String> values(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : JSON.readValue(json, STRINGS)) {
            if (value != null && !value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    private static void bindAlarm(PreparedStatement statement, boolean postgres,
                                  String tenant, String caseId, String alarmId) throws Exception {
        statement.setString(1, UUID.nameUUIDFromBytes((tenant + "\u0000" + alarmId)
                .getBytes(StandardCharsets.UTF_8)).toString());
        statement.setString(2, tenant);
        statement.setString(3, alarmId);
        statement.setString(4, caseId);
        statement.setObject(5, java.sql.Timestamp.from(Instant.now()));
        if (!postgres) {
            statement.setString(6, tenant);
            statement.setString(7, alarmId);
        }
    }

    private static void bindRule(PreparedStatement statement, boolean postgres,
                                 String tenant, String caseId, String ruleId) throws Exception {
        statement.setString(1, UUID.nameUUIDFromBytes((tenant + "\u0000" + caseId + "\u0000" + ruleId)
                .getBytes(StandardCharsets.UTF_8)).toString());
        statement.setString(2, tenant);
        statement.setString(3, caseId);
        statement.setString(4, ruleId);
        statement.setObject(5, java.sql.Timestamp.from(Instant.now()));
        if (!postgres) {
            statement.setString(6, tenant);
            statement.setString(7, caseId);
            statement.setString(8, ruleId);
        }
    }
}
