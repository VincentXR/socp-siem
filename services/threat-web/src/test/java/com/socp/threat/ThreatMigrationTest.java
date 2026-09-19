package com.socp.threat;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreatMigrationTest {

    /** Published migration: its exact text is part of its Flyway checksum. */
    private static final String PUBLISHED_V2 = "/db/migration/V2__stix_indicator_metadata.sql";
    /** Newer version that replays the confidence column type change. */
    private static final String CONFIDENCE_TYPE_REPLAY = "/db/migration/V5__stix_confidence_double_precision.sql";

    @Test
    void iocTenantValueIndexIsCreatedByTheMigrationSequence() throws Exception {
        String url = "jdbc:h2:mem:threat_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                             + "WHERE INDEX_NAME = 'IDX_T_IOC_TENANT_VALUE'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void everyVersionAppliesInOrderOnAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:threat_migration_sequence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration").load();

        // V5 (confidence type replay) is the current head of the sequence.
        assertEquals(5, flyway.migrate().migrationsExecuted);
        var applied = flyway.info().applied();
        assertEquals("[1, 2, 3, 4, 5]",
                Arrays.stream(applied).map(each -> String.valueOf(each.getVersion())).toList().toString());
        assertTrue(Arrays.stream(applied).allMatch(each -> each.getState().isApplied()),
                "no version may fail or stay pending on an empty database");
    }

    @Test
    void confidenceColumnIsDoublePrecisionAfterTheReplay() throws Exception {
        String url = "jdbc:h2:mem:threat_migration_confidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'T_IOC' AND COLUMN_NAME = 'CONFIDENCE'");
             var result = statement.executeQuery()) {
            assertTrue(result.next(), "t_ioc.confidence must exist after the migration sequence");
            assertEquals("DOUBLE PRECISION", result.getString(1),
                    "V5 must leave confidence as the type IocEntity's Double maps to");
        }
    }

    @Test
    void publishedV2IsNotRewrittenAndTheTypeChangeIsReplayedByANewerVersion() throws Exception {
        String published = readMigration(PUBLISHED_V2);
        assertTrue(published.contains("ALTER TABLE t_ioc ADD COLUMN IF NOT EXISTS confidence DOUBLE;"),
                "V2 is published: reverting it to its original text is the only allowed change");
        assertFalse(published.contains("DOUBLE PRECISION"),
                "V2 must not carry afc86f48's in-place rewrite; it invalidates the stored checksum "
                        + "and Flyway never re-runs an applied version on existing databases");

        String replay = readMigration(CONFIDENCE_TYPE_REPLAY);
        assertTrue(replay.contains("ALTER TABLE t_ioc ALTER COLUMN confidence TYPE DOUBLE PRECISION;"),
                "the intended type must be re-delivered by a higher version, not by editing V2");
    }

    private static String readMigration(String resource) throws Exception {
        try (InputStream in = ThreatMigrationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be packaged on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
