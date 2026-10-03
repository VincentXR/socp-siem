package com.socp.asset.web.persistence.store;
import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the same real concurrent repository contract against PostgreSQL in integration CI. */
@Testcontainers
@EnabledIfEnvironmentVariable(named="SOCP_TESTCONTAINERS", matches="true")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class AssetCollectionPostgresTest extends AssetCollectionPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("asset").withUsername("socp").withPassword("socp");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        properties.add("spring.flyway.enabled",()->true);
        properties.add("spring.datasource.hikari.maximum-pool-size",()->10);
    }
}
