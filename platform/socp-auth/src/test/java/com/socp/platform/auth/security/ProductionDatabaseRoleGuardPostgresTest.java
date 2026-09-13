package com.socp.platform.auth.security;

import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real PostgreSQL proof that the prod startup guard distinguishes runtime roles. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class ProductionDatabaseRoleGuardPostgresTest {

    private static final String RUNTIME_USER = "socp_runtime";
    private static final String RUNTIME_PASSWORD = "runtime-secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("role_guard")
            .withUsername("socp_admin")
            .withPassword("admin-secret");

    private static DataSource adminDataSource;
    private static DataSource runtimeDataSource;

    @BeforeAll
    static void createRestrictedRuntimeRole() throws Exception {
        try (var connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.execute("create role " + RUNTIME_USER
                    + " login password '" + RUNTIME_PASSWORD
                    + "' nosuperuser nobypassrls");
        }

        adminDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        runtimeDataSource = dataSource(RUNTIME_USER, RUNTIME_PASSWORD);
    }

    @Test
    void acceptsNonBypassRuntimeRole() {
        ProductionDatabaseRoleGuard guard = guard(runtimeDataSource);

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void rejectsBootstrapSuperuserRole() {
        ProductionDatabaseRoleGuard guard = guard(adminDataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.run(null));
        assertTrue(failure.getMessage().contains("bypasses RLS"));
    }

    private static ProductionDatabaseRoleGuard guard(DataSource dataSource) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", POSTGRES.getJdbcUrl())
                .withProperty("socp.tenant.rls.enabled", "true");
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return new ProductionDatabaseRoleGuard(environment, provider);
    }

    private static DataSource dataSource(String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }
}
