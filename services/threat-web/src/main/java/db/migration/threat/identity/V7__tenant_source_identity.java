package db.migration.threat.identity;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.Locale;

/** Preserve public IDs and source evidence while introducing collision-safe tenant business keys. */
public class V7__tenant_source_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE t_ioc ADD COLUMN identity_key VARCHAR(64)");
            ddl.execute("ALTER TABLE t_ioc ADD COLUMN version BIGINT NOT NULL DEFAULT 0");
        }
        try (var select = connection.prepareStatement(
                "SELECT id, tenant_id, type, ioc_value, source, external_id FROM t_ioc");
             PreparedStatement update = connection.prepareStatement(
                "UPDATE t_ioc SET identity_key = ?, tenant_id = ?, type = ?, ioc_value = ?, source = ?, external_id = ? WHERE id = ?")) {
            select.setFetchSize(500);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String type = required(rows.getString("type"), "type").trim().toUpperCase(Locale.ROOT);
                    String value = required(rows.getString("ioc_value"), "value").trim().toLowerCase(Locale.ROOT);
                    String source = fallback(rows.getString("source"), "manual");
                    String external = fallback(rows.getString("external_id"), "");
                    update.setString(1, identity(type, value, source, external));
                    update.setString(2, fallback(rows.getString("tenant_id"), "default"));
                    update.setString(3, type);
                    update.setString(4, value);
                    update.setString(5, source);
                    update.setString(6, external.isEmpty() ? null : external);
                    update.setString(7, rows.getString("id"));
                    update.executeUpdate();
                }
            }
        }
        try (var ddl = connection.createStatement()) {
            // Deliberately fail on conflicting historical facts; never delete/merge source evidence.
            ddl.execute("CREATE UNIQUE INDEX uk_t_ioc_tenant_identity ON t_ioc (tenant_id, identity_key)");
            ddl.execute("ALTER TABLE t_ioc ALTER COLUMN identity_key SET NOT NULL");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException("IOC migration requires non-empty " + field);
        return value;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // Frozen V7 encoding: keep independent of subsequently changed domain classes.
    private static String identity(String type, String value, String source, String external) throws Exception {
        String[] parts = external.isEmpty() ? new String[] {"manual", source, type, value}
                : new String[] {"external", source, external};
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
