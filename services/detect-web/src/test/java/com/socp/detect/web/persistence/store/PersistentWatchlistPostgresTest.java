package com.socp.detect.web.persistence.store;

import com.socp.platform.test.MiddlewareImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistentWatchlistPostgresTest extends PersistentWatchlistStateStoreTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("watchlists").withUsername("socp").withPassword("socp-test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '5s'");
    }

    @Autowired private JdbcTemplate upgradeJdbc;

    @Test void upgradeBackfillsCountsInBatchesAndPreservesPayloadsAndTombstones() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("watchlist_upgrade").locations("classpath:db/migration").target("26").load().migrate();
        for (int i = 0; i < 102; i++) {
            upgradeJdbc.update("insert into watchlist_upgrade.t_watchlist "
                    + "(tenant_id, list_name, values_json, deleted, row_version, updated_at) "
                    + "values ('tenant-a', ?, '[\"one\",\"two\",\"one\"]', ?, 7, current_timestamp)", "list-" + i, i == 0);
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("watchlist_upgrade").locations("classpath:db/migration").load().migrate();
        assertEquals(101, upgradeJdbc.queryForObject(
                "select count(*) from watchlist_upgrade.t_watchlist where value_count = 2", Integer.class));
        var tombstone = upgradeJdbc.queryForMap("select * from watchlist_upgrade.t_watchlist where list_name = 'list-0'");
        assertEquals(0, tombstone.get("value_count"));
        assertEquals(true, tombstone.get("deleted"));
        assertEquals(7L, tombstone.get("row_version"));
        assertEquals("[\"one\",\"two\",\"one\"]", tombstone.get("values_json"));
    }
}
