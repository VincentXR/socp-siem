package com.socp.soar.web.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Populated V26 upgrades exercise the actual frozen migration, including all keyset batches. */
class SoarCatalogUpgradePersistenceTest {
    private static final int ROWS = 503;
    private static final String DEFINITION = "{\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"enabled\":{\"type\":\"boolean\",\"default\":false},\"count\":{\"type\":\"integer\",\"default\":0}},"
            + "\"required\":[\"count\"]},\"nodes\":[]}";

    protected String url() { return "jdbc:h2:mem:catalog_upgrade_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"; }
    protected String username() { return "sa"; }
    protected String password() { return ""; }

    @Test void upgradesPopulatedLegacyRowsWithoutTruncatingOrChangingSourceJson() throws Exception {
        String url = url();
        Flyway.configure().dataSource(url, username(), password()).locations("classpath:db/migration")
                .target("26").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, username(), password())) {
            seedLegacy(connection);
        }
        Flyway upgraded = Flyway.configure().dataSource(url, username(), password())
                .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("29");
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        upgraded.validate();

        try (Connection connection = DriverManager.getConnection(url, username(), password());
             var query = connection.prepareStatement("select p.id,p.tenant_id,p.tags_json,p.tag_tokens,"
                     + "v.risk_summary_json,v.high_risk_action_count,v.action_count,v.definition_json,"
                     + "p.row_version,v.row_version from t_soar_playbook p join t_soar_playbook_version v "
                     + "on v.playbook_id=p.id and v.tenant_id=p.tenant_id order by p.id");
             var rows = query.executeQuery()) {
            int seen = 0;
            while (rows.next()) {
                int i = Integer.parseInt(rows.getString(1).substring(3));
                assertThat(rows.getString(2)).isEqualTo(tenant(i));
                assertThat(rows.getString(3)).isEqualTo(tags(i));
                assertThat(rows.getString(4)).as("tag metadata row %s", i).isEqualTo(expectedTokens(i));
                assertThat(rows.getString(5)).isEqualTo(risk(i));
                int[] counts = expectedRisk(i);
                assertThat(rows.getInt(6)).as("high risk row %s", i).isEqualTo(counts[0]);
                assertThat(rows.getInt(7)).as("action count row %s", i).isEqualTo(counts[1]);
                assertThat(rows.getString(8)).isEqualTo(DEFINITION);
                assertThat(rows.getLong(9)).isEqualTo(7);
                assertThat(rows.getLong(10)).isEqualTo(9);
                seen++;
            }
            assertThat(seen).isEqualTo(ROWS);
            try (var origins = connection.prepareStatement("select id,origin_alarm_id,origin_case_id,input_json "
                    + "from t_soar_run order by id"); var runs = origins.executeQuery()) {
                String[] types = {"ALARM", "alert", "Alert.Created", "CASE", "incident", "Incident.Created", "asset", null};
                for (int i = 0; i < types.length; i++) {
                    assertThat(runs.next()).isTrue();
                    assertThat(runs.getString(1)).isEqualTo("run-" + i);
                    assertThat(runs.getString(2)).isEqualTo(i < 3 ? "subject-" + i : null);
                    assertThat(runs.getString(3)).isEqualTo(i >= 3 && i < 6 ? "subject-" + i : null);
                    assertThat(runs.getString(4)).isEqualTo("{\"enabled\":false,\"count\":0}");
                }
                assertThat(runs.next()).isFalse();
            }
        }
    }

    private static void seedLegacy(Connection connection) throws Exception {
        try (PreparedStatement playbook = connection.prepareStatement("insert into t_soar_playbook "
                + "(id,tenant_id,name,tags_json,status,row_version,created_at,updated_at) "
                + "values (?,?,?,?,'ACTIVE',7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
             PreparedStatement version = connection.prepareStatement("insert into t_soar_playbook_version "
                + "(id,tenant_id,playbook_id,version_no,status,schema_version,definition_json,definition_hash,"
                + "risk_summary_json,created_by,created_at,updated_at,row_version) "
                + "values (?,?,?,1,'PUBLISHED','2.0',?,'hash',?,'upgrade-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,9)")) {
            // Reverse insertion order and >2 keyset batches prevent accidental first-page-only backfills.
            for (int i = ROWS - 1; i >= 0; i--) {
                String id = String.format("pb-%04d", i);
                playbook.setString(1, id); playbook.setString(2, tenant(i));
                playbook.setString(3, "Legacy " + i); playbook.setString(4, tags(i)); playbook.addBatch();
                version.setString(1, "version-" + id); version.setString(2, tenant(i)); version.setString(3, id);
                version.setString(4, DEFINITION); version.setString(5, risk(i)); version.addBatch();
            }
            playbook.executeBatch();
            version.executeBatch();
        }
        String[] types = {"ALARM", "alert", "Alert.Created", "CASE", "incident", "Incident.Created", "asset", null};
        try (var run = connection.prepareStatement("insert into t_soar_run "
                + "(id,tenant_id,request_id,execution_series_id,playbook_id,playbook_version_id,playbook_version_no,"
                + "definition_hash,trigger_type,subject_type,subject_id,status,input_json,requested_by,created_at,updated_at,row_version) "
                + "values (?,?,?,?,?,?,1,'hash','MANUAL',?,?,'SUCCEEDED',?, 'upgrade-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)")) {
            for (int i = 0; i < types.length; i++) {
                String playbook = String.format("pb-%04d", i);
                run.setString(1, "run-" + i); run.setString(2, tenant(i)); run.setString(3, "request-" + i);
                run.setString(4, "series-" + i); run.setString(5, playbook); run.setString(6, "version-" + playbook);
                run.setString(7, types[i]); run.setString(8, "subject-" + i);
                run.setString(9, "{\"enabled\":false,\"count\":0}"); run.addBatch();
            }
            run.executeBatch();
        }
    }

    private static String tenant(int i) { return i % 2 == 0 ? "upgrade-a" : "upgrade-b"; }
    private static String tags(int i) {
        return switch (i % 7) {
            case 0 -> "[\"Blue\",\"BLUE\",\"ops%_\"]";
            case 1 -> "[\"quo\\\"te\",\"路径\",\"back\\\\slash\"]";
            case 2 -> "[]";
            case 3 -> null;
            case 4 -> "broken-json";
            case 5 -> "{\"tag\":\"blue\"}";
            default -> "[\"uncommon-" + i + "\"]";
        };
    }
    private static String expectedTokens(int i) {
        return switch (i % 7) {
            case 0 -> joinTokens("blue", "ops%_");
            case 1 -> joinTokens("quo\"te", "路径", "back\\slash");
            case 6 -> joinTokens("uncommon-" + i);
            default -> "";
        };
    }
    private static String joinTokens(String... tags) {
        // Independent, explicit fixture oracle, rather than calling the application or migration helper.
        return java.util.Arrays.stream(tags).map(tag -> "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tag.getBytes(StandardCharsets.UTF_8)) + "|").sorted().reduce("", String::concat);
    }
    private static String risk(int i) {
        return switch (i % 6) {
            case 0 -> "{\"highRiskActionCount\":2,\"actionCount\":7}";
            case 1 -> "{\"highRiskActionCount\":0,\"actionCount\":3}";
            case 2 -> "{\"highRiskActionCount\":-8,\"actionCount\":-2}";
            case 3 -> null;
            case 4 -> "broken-json";
            default -> "{}";
        };
    }
    private static int[] expectedRisk(int i) {
        return switch (i % 6) { case 0 -> new int[] {2, 7}; case 1 -> new int[] {0, 3}; default -> new int[] {0, 0}; };
    }
}
