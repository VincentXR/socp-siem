package com.socp.platform.tenant.persistence;

import com.socp.platform.test.MiddlewareImages;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
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
        TenantContext.runWith("tenant-a", () -> insert("tenant-test-a-1", "tenant-a"));
        TenantContext.runWith("tenant-b", () -> insert("tenant-test-b-1", "tenant-b"));

        TenantContext.runWith("tenant-a", () -> assertEquals(1,
                countRows(tenantDataSource, "tenant-test-%")));
        TenantContext.runWith("tenant-b", () -> assertEquals(1,
                countRows(tenantDataSource, "tenant-test-%")));
        assertEquals(0, countRows(tenantDataSource, "tenant-test-%"),
                "a missing scope must fail closed");
        TenantContext.runAsSystem(() -> assertEquals(2,
                countRows(tenantDataSource, "tenant-test-%")));

        TenantContext.runWith("tenant-a", () -> assertThrows(SQLException.class,
                () -> insertChecked("bad", "tenant-b")));
    }

    @Test
    void pooledConnectionReassertsScopeAcrossRollbackPreparedReuseAndBatch() throws Exception {
        PGSimpleDataSource physicalSource = new PGSimpleDataSource();
        physicalSource.setURL(POSTGRES.getJdbcUrl());
        physicalSource.setUser("socp_app");
        physicalSource.setPassword("app-secret");
        SingleConnectionDataSource onePhysicalConnection =
                new SingleConnectionDataSource(physicalSource.getConnection(), true);
        DataSource pooled = new TenantRlsDataSource(onePhysicalConnection);
        try {
            TenantContext.runWith("pool-a", () -> insert(pooled, "pool-a-1", "pool-a"));

            TenantContext.set("pool-a");
            try (Connection connection = pooled.getConnection()) {
                connection.setAutoCommit(false);
                try (var prepared = connection.prepareStatement(
                        "select count(*) from tenant_item where id like 'pool-%'")) {
                    assertEquals(1, count(prepared));
                    TenantContext.set("pool-b");
                    assertEquals(0, count(prepared),
                            "a prepared statement must observe the current scope at execution");
                    connection.rollback();
                    TenantContext.clear();
                    assertEquals(0, count(prepared),
                            "rollback must not restore a stale tenant into a no-scope execution");
                }
                // Mirror a pool's reset before the physical connection is
                // borrowed again by a different tenant.
                connection.setAutoCommit(true);
            }

            TenantContext.set("pool-b");
            try (Connection connection = pooled.getConnection();
                 var batch = connection.prepareStatement(
                         "insert into tenant_item(id, tenant_id, value) values (?, ?, 'batch')")) {
                batch.setString(1, "pool-b-1"); batch.setString(2, "pool-b"); batch.addBatch();
                batch.setString(1, "pool-b-2"); batch.setString(2, "pool-b"); batch.addBatch();
                assertEquals(2, batch.executeBatch().length);
            }

            TenantContext.runWith("pool-a", () -> assertEquals(1, countRows(pooled, "pool-%")));
            TenantContext.runWith("pool-b", () -> assertEquals(2, countRows(pooled, "pool-%")));
            TenantContext.clear();
            assertEquals(0, countRows(pooled, "pool-%"));
        } finally {
            onePhysicalConnection.destroy();
        }
    }

    private static void insert(String id, String tenantId) {
        insert(tenantDataSource, id, tenantId);
    }

    private static void insert(DataSource dataSource, String id, String tenantId) {
        try {
            insertChecked(dataSource, id, tenantId);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void insertChecked(String id, String tenantId) throws SQLException {
        insertChecked(tenantDataSource, id, tenantId);
    }

    private static void insertChecked(DataSource dataSource, String id, String tenantId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "insert into tenant_item(id, tenant_id, value) values (?, ?, 'test')")) {
            statement.setString(1, id);
            statement.setString(2, tenantId);
            statement.executeUpdate();
        }
    }

    private static int countRows() {
        return countRows(tenantDataSource, "%");
    }

    private static int countRows(DataSource dataSource, String idPattern) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select count(*) from tenant_item where id like ?")) {
            statement.setString(1, idPattern);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int count(java.sql.PreparedStatement statement) throws SQLException {
        try (var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
