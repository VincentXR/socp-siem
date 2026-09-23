package com.socp.detect.web.persistence.store;

import com.socp.platform.test.MiddlewareImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RuleCatalogPostgresTest extends RuleCatalogPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("detect").withUsername("socp").withPassword("socp-test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void populatedUpgradeBackfillsBeyondOneBatchWithoutChangingSpecs() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("catalog_upgrade").locations("classpath:db/migration").target("24").load().migrate();
        String payload = "{\"id\":\"old\",\"name\":\"Legacy % name\",\"type\":\"pattern\",\"enabled\":false,"
                + "\"mitre\":\"T1110\",\"match\":[{\"op\":\"inlist\",\"value\":\"a_b%\"}]}";
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var insert = connection.prepareStatement("insert into catalog_upgrade.t_rule(id,rule_id,tenant_id,spec) values(?,?,?,?)")) {
            for (int i = 0; i < 501; i++) {
                insert.setString(1, "legacy-" + i);
                insert.setString(2, "legacy-" + i);
                insert.setString(3, "legacy-tenant");
                insert.setString(4, payload);
                insert.addBatch();
            }
            insert.executeBatch();
            insert.setString(1, "legacy-packaged");
            insert.setString(2, "AUTH-BRUTE");
            insert.setString(4, "{\"id\":\"AUTH-BRUTE\",\"name\":\"Old packaged\",\"enabled\":false}");
            insert.executeUpdate();
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("catalog_upgrade").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select * from catalog_upgrade.t_rule order by id")) {
            int count = 0;
            while (rows.next()) {
                if ("AUTH-BRUTE".equals(rows.getString("rule_id"))) {
                    assertEquals("ACTIVE", rows.getString("catalog_status"));
                    assertEquals("T1110", rows.getString("catalog_techniques"));
                    assertEquals("{\"id\":\"AUTH-BRUTE\",\"name\":\"Old packaged\",\"enabled\":false}", rows.getString("spec"));
                    continue;
                }
                count++;
                assertEquals(payload, rows.getString("spec"));
                assertEquals("DISABLED", rows.getString("catalog_status"));
                assertEquals("Legacy % name", rows.getString("catalog_name"));
                assertEquals("T1110", rows.getString("catalog_techniques"));
                assertTrue(rows.getString("catalog_references").startsWith("|"));
            }
            assertEquals(501, count);
        }
    }
}
