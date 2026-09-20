package com.socp.threat;

import com.socp.platform.test.MiddlewareImages;
import com.socp.threat.web.config.LegacyConfidenceTypeCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class ThreatPostgresMigrationTest {
    @Test
    void upgradesPublishedV2OnPostgresAndRemovesOnlyItsCompatibilityAlias() throws Exception {
        try (var postgres = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
                .withEnv("POSTGRES_DB", "threat").withEnv("POSTGRES_USER", "socp")
                .withEnv("POSTGRES_PASSWORD", "socp-test").withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))) {
            postgres.start();
            String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/threat";
            Flyway.configure().dataSource(url, "socp", "socp-test").target("1")
                    .locations("classpath:db/migration").load().migrate();
            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_ioc(id, tenant_id, ioc_value) VALUES ('legacy', 'tenant-a', 'example.test')");
            }
            Flyway flyway = Flyway.configure().dataSource(url, "socp", "socp-test")
                    .locations("classpath:db/migration").callbacks(new LegacyConfidenceTypeCallback()).load();
            assertEquals(4, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.execute("UPDATE t_ioc SET confidence = 87.5 WHERE id = 'legacy'");
                try (var rows = statement.executeQuery("SELECT ioc_value, confidence, pg_typeof(confidence)::text FROM t_ioc")) {
                    assertTrue(rows.next());
                    assertEquals("example.test", rows.getString(1));
                    assertEquals(87.5, rows.getDouble(2));
                    assertEquals("double precision", rows.getString(3));
                }
                try (var rows = statement.executeQuery("SELECT to_regtype('double') IS NOT NULL")) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean(1));
                }
            }
        }
    }
}
