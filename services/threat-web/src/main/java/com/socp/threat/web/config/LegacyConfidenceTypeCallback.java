package com.socp.threat.web.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/** Preserves the published V2 checksum while accepting its legacy DOUBLE spelling on PostgreSQL. */
@Component
public class LegacyConfidenceTypeCallback implements Callback {
    private static final String MARKER = "socp-threat-v2-confidence-compatibility";

    @Override
    public boolean supports(Event event, Context context) {
        return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE)
                && context.getMigrationInfo() != null
                && "2".equals(String.valueOf(context.getMigrationInfo().getVersion()));
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            Connection connection = context.getConnection();
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
            try (var statement = connection.createStatement()) {
                if (event == Event.BEFORE_EACH_MIGRATE) {
                    boolean exists;
                    try (var rows = statement.executeQuery("SELECT to_regtype('double') IS NOT NULL")) {
                        rows.next();
                        exists = rows.getBoolean(1);
                    }
                    if (!exists) {
                        statement.execute("CREATE DOMAIN \"double\" AS DOUBLE PRECISION");
                        statement.execute("COMMENT ON DOMAIN \"double\" IS '" + MARKER + "'");
                    }
                } else {
                    boolean owned;
                    try (var rows = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_type "
                            + "WHERE oid = to_regtype('double') AND typtype = 'd' "
                            + "AND typbasetype = 'double precision'::regtype "
                            + "AND obj_description(oid, 'pg_type') = '" + MARKER + "')")) {
                        rows.next();
                        owned = rows.getBoolean(1);
                    }
                    if (owned) {
                        // Remove the compatibility dependency before dropping only our own alias.
                        // Never use CASCADE or remove a pre-existing operator-managed type.
                        statement.execute("ALTER TABLE t_ioc ALTER COLUMN confidence TYPE DOUBLE PRECISION");
                        statement.execute("DROP DOMAIN \"double\"");
                    }
                }
            }
        } catch (SQLException failure) {
            throw new FlywayException("Unable to apply published STIX V2 on PostgreSQL", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return "legacy-stix-confidence-type";
    }
}
