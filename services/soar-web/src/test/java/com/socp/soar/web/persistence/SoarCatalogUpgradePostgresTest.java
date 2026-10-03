package com.socp.soar.web.persistence;

import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class SoarCatalogUpgradePostgresTest extends SoarCatalogUpgradePersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("soar_catalog_upgrade").withUsername("socp").withPassword("socp-test");

    @Override protected String url() { return POSTGRES.getJdbcUrl(); }
    @Override protected String username() { return POSTGRES.getUsername(); }
    @Override protected String password() { return POSTGRES.getPassword(); }
}
