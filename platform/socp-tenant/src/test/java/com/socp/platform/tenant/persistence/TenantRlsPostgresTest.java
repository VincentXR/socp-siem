package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real PostgreSQL proof that the connection scope and RLS policy isolate tenants. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class TenantRlsPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tenant_rls")
            .withUsername("socp_admin")
            .withPassword("admin-secret");

    private static DataSource tenantDataSource;

    @BeforeAll
    static void createSchemaAndRestrictedApplicationRole() throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.execute("create role socp_app login password 'app-secret' nosuperuser nobypassrls");
            statement.execute("create table tenant_item (id varchar(64) primary key, tenant_id varchar(64) not null, value varchar(255))");
            statement.execute("alter table tenant_item enable row level security");
            statement.execute("alter table tenant_item force row level security");
            statement.execute("create policy socp_tenant_isolation on tenant_item "
                    + "using (current_setting('socp.tenant_id', true) = '*' "
                    + "or tenant_id = current_setting('socp.tenant_id', true)) "
                    + "with check (current_setting('socp.tenant_id', true) = '*' "
                    + "or tenant_id = current_setting('socp.tenant_id', true))");
            statement.execute("grant select, insert, update, delete on tenant_item to socp_app");
        }

        PGSimpleDataSource delegate = new PGSimpleDataSource();
        delegate.setURL(POSTGRES.getJdbcUrl());
        delegate.setUser("socp_app");
        delegate.setPassword("app-secret");
        tenantDataSource = new TenantRlsDataSource(delegate);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void tenantScopeFiltersReadsAndRejectsCrossTenantWrites() {
        TenantContext.runWith("tenant-a", () -> insert("a-1", "tenant-a"));
        TenantContext.runWith("tenant-b", () -> insert("b-1", "tenant-b"));

        TenantContext.runWith("tenant-a", () -> assertEquals(1, countRows()));
        TenantContext.runWith("tenant-b", () -> assertEquals(1, countRows()));
        assertEquals(0, countRows(), "a missing scope must fail closed");
        TenantContext.runAsSystem(() -> assertEquals(2, countRows()));

        TenantContext.runWith("tenant-a", () -> assertThrows(SQLException.class,
                () -> insertChecked("bad", "tenant-b")));
    }

    private static void insert(String id, String tenantId) {
        try {
            insertChecked(id, tenantId);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void insertChecked(String id, String tenantId) throws SQLException {
        try (Connection connection = tenantDataSource.getConnection();
             var statement = connection.prepareStatement(
                     "insert into tenant_item(id, tenant_id, value) values (?, ?, 'test')")) {
            statement.setString(1, id);
            statement.setString(2, tenantId);
            statement.executeUpdate();
        }
    }

    private static int countRows() {
        try (Connection connection = tenantDataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from tenant_item")) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
