package db.migration.detect.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Frozen JSON backfill for PostgreSQL and H2; no database-specific JSON operators. */
public class V25__rule_catalog_metadata extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    // Frozen v2026.09.13 defaults used by DetectionContentCatalog.enrich before
    // this migration. Old packaged specs may omit these fields; reading a newer
    // mutable manifest here would change historical upgrade results.
    private record Defaults(String status, String techniques) {}
    private static final Map<String, Defaults> LEGACY_DEFAULTS = Map.ofEntries(
            Map.entry("AUTH-BRUTE", new Defaults("ACTIVE", "T1110")),
            Map.entry("AUTH-BRUTE-SUCCESS", new Defaults("ACTIVE", "T1110,T1078")),
            Map.entry("AUTH-PRIVESC", new Defaults("ACTIVE", "T1548")),
            Map.entry("CRED-DUMP", new Defaults("ACTIVE", "T1003")),
            Map.entry("AUTH-NEW-ADMIN", new Defaults("ACTIVE", "T1098")),
            Map.entry("AUTH-ROOT-LOGIN", new Defaults("ACTIVE", "T1078")),
            Map.entry("LATERAL-RDP", new Defaults("ACTIVE", "T1021.001")),
            Map.entry("LATERAL-SMB", new Defaults("ACTIVE", "T1021.002")),
            Map.entry("EXEC-POWERSHELL", new Defaults("ACTIVE", "T1059.001")),
            Map.entry("EXEC-SHELL", new Defaults("ACTIVE", "T1059")),
            Map.entry("PERSIST-CRON", new Defaults("ACTIVE", "T1053.003")),
            Map.entry("PERSIST-TASK", new Defaults("ACTIVE", "T1053.005")),
            Map.entry("EVADE-LOGCLEAR", new Defaults("ACTIVE", "T1070.001")),
            Map.entry("IOC-BLOCKED-IP", new Defaults("ACTIVE", "T1071")),
            Map.entry("WEB-SQLI", new Defaults("ACTIVE", "T1190")),
            Map.entry("WEB-TRAVERSAL", new Defaults("ACTIVE", "T1006")),
            Map.entry("C2-BEACON", new Defaults("ACTIVE", "T1071.001")),
            Map.entry("EXFIL-LARGE", new Defaults("ACTIVE", "T1041")),
            Map.entry("DOS-FLOOD", new Defaults("ACTIVE", "T1498")),
            Map.entry("CORR-FAIL-SUDO", new Defaults("ACTIVE", "T1110,T1548")),
            Map.entry("RISK-ENTITY-SPIKE", new Defaults("ACTIVE", "T1078")),
            Map.entry("RARE-PROCESS", new Defaults("ACTIVE", "T1059")),
            Map.entry("RARE-DOMAIN", new Defaults("ACTIVE", "T1071.004")),
            Map.entry("BASELINE-AUTH-VOLUME", new Defaults("ACTIVE", "T1078")),
            Map.entry("CORR-ATTACK-SIGNALS", new Defaults("ACTIVE", "T1078,T1548,T1059")),
            Map.entry("EXEC-SUSPICIOUS-SHELL", new Defaults("ACTIVE", "T1059")),
            Map.entry("FW-SCAN", new Defaults("ACTIVE", "T1046")),
            Map.entry("MAL-C2", new Defaults("ACTIVE", "T1071")),
            Map.entry("PHISH-MAIL", new Defaults("ACTIVE", "T1566")),
            Map.entry("RANSOM-ENCRYPT", new Defaults("ACTIVE", "T1486")),
            Map.entry("UEBA-AUTH-SPIKE", new Defaults("ACTIVE", "T1110")),
            Map.entry("UEBA-NEW-DEST", new Defaults("ACTIVE", "T1071")),
            Map.entry("UEBA-NEW-GEO", new Defaults("ACTIVE", "T1078")),
            Map.entry("UEBA-NEW-PROCESS", new Defaults("ACTIVE", "T1059")),
            Map.entry("UEBA-USER-VOLUME", new Defaults("ACTIVE", "T1078")),
            Map.entry("WATCH-BLOCKED-IP", new Defaults("ACTIVE", "T1071")),
            Map.entry("WATCH-CROWN-JEWEL", new Defaults("ACTIVE", "T1021")),
            Map.entry("WATCH-PRIV-ACCOUNT", new Defaults("ACTIVE", "T1078")),
            Map.entry("WEB-ATTACK", new Defaults("ACTIVE", "T1190")));

    @Override
    public Integer getChecksum() { return 250001; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("alter table t_rule add column catalog_name text");
            statement.execute("alter table t_rule add column catalog_type text");
            statement.execute("alter table t_rule add column catalog_status varchar(32)");
            statement.execute("alter table t_rule add column catalog_search text");
            statement.execute("alter table t_rule add column catalog_references text");
            statement.execute("alter table t_rule add column catalog_techniques text");
        }
        String last = null;
        while (true) {
            List<Row> rows = new ArrayList<>();
            String query = "select id, spec from t_rule " + (last == null ? "" : "where id > ? ")
                    + "order by id limit 250";
            try (var select = connection.prepareStatement(query)) {
                if (last != null) select.setString(1, last);
                try (var results = select.executeQuery()) {
                    while (results.next()) rows.add(new Row(results.getString(1), results.getString(2)));
                }
            }
            if (rows.isEmpty()) break;
            try (var update = connection.prepareStatement("update t_rule set catalog_name = ?, catalog_type = ?, "
                    + "catalog_status = ?, catalog_search = ?, catalog_references = ?, catalog_techniques = ? where id = ?")) {
                for (Row row : rows) {
                    Metadata fields = Metadata.from(JSON.readValue(row.spec(), OBJECT));
                    update.setString(1, fields.name());
                    update.setString(2, fields.type());
                    update.setString(3, fields.status());
                    update.setString(4, fields.search());
                    update.setString(5, fields.references());
                    update.setString(6, fields.techniques());
                    update.setString(7, row.id());
                    update.addBatch();
                }
                update.executeBatch();
            }
            last = rows.getLast().id();
        }
        try (var statement = connection.createStatement()) {
            for (String column : List.of("catalog_name", "catalog_type", "catalog_status", "catalog_search",
                    "catalog_references", "catalog_techniques")) {
                statement.execute("alter table t_rule alter column " + column + " set not null");
            }
            statement.execute("create index idx_rule_catalog_status on t_rule (tenant_id, catalog_status, rule_id)");
        }
    }

    private record Row(String id, String spec) {}

    // Freeze the conversion with this migration. Runtime metadata may evolve in
    // a later migration, but applying V25 must never reinterpret an old database.
    private record Metadata(String name, String type, String status, String search,
                                      String references, String techniques) {
        public static Metadata from(Map<String, Object> spec) {
            Defaults defaults = LEGACY_DEFAULTS.get(text(spec.get("id")));
            if (defaults != null) {
                spec = new java.util.LinkedHashMap<>(spec);
                if (!spec.containsKey("status")) spec.put("status", defaults.status());
                if (!spec.containsKey("mitreIds")) spec.put("mitreIds", defaults.techniques());
            }
            String name = text(spec.get("name"));
            String type = text(spec.get("type")).toLowerCase(Locale.ROOT);
            String status = text(spec.get("status")).toUpperCase(Locale.ROOT);
            if (!spec.containsKey("status")) status = Boolean.parseBoolean(text(spec.getOrDefault("enabled", true)))
                    ? "ACTIVE" : "DISABLED";
            Set<String> references = new LinkedHashSet<>();
            // These are the executable condition trees. Extension metadata is not
            // itself a rule dependency.
            for (String key : List.of("match", "matchAny", "steps", "whitelist", "allowlist")) {
                collectReferences(spec.get(key), references);
            }
            Set<String> techniques = new TreeSet<>();
            addTechniques(spec.get("mitre"), techniques);
            addTechniques(spec.get("mitreIds"), techniques);
            return new Metadata(name, type, status,
                    (text(spec.get("id")) + "\n" + name + "\n" + type).toLowerCase(Locale.ROOT),
                    String.join("", references), String.join("\n", techniques));
        }

        public static String referenceToken(String name) {
            return "|" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(name.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) + "|";
        }

        private static void collectReferences(Object value, Set<String> references) {
            if (value instanceof List<?> values) {
                values.forEach(item -> collectReferences(item, references));
            } else if (value instanceof Map<?, ?> condition) {
                String op = text(condition.get("op")).toLowerCase(Locale.ROOT);
                if (("inlist".equals(op) || "notinlist".equals(op)) && condition.get("value") != null) {
                    references.add(referenceToken(text(condition.get("value"))));
                }
            }
        }

        private static void addTechniques(Object value, Set<String> techniques) {
            if (value instanceof List<?> values) {
                values.forEach(item -> addTechniques(item, techniques));
            } else if (value instanceof String text) {
                for (String id : text.toUpperCase(Locale.ROOT).split("[\\s,;]+")) {
                    if (id.matches("T[0-9]{4}(?:\\.[0-9]{3})?")) techniques.add(id);
                }
            }
        }

        private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    }
}
