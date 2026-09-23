package com.socp.detect.web.engine;

import com.socp.platform.test.MiddlewareImages;
import com.socp.platform.tenant.context.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DetectionAlertOutboxPostgresTest extends DetectionAlertOutboxPersistenceTest {
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
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '5s'");
    }

    @Autowired private DataSource dataSource;

    @Test
    void recoverySkipsAnotherTransactionsLockAndPreservesItsCompletion() throws Exception {
        var locked = processing(1, false);
        var available = processing(1, false);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "select alert_id from t_detection_alert_outbox where alert_id = ? for update")) {
                statement.setString(1, locked.getAlertId());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            recover();
            assertEquals("PENDING", load(available).getStatus());
            assertEquals("PROCESSING", load(locked).getStatus());
            try (var statement = connection.prepareStatement(
                    "update t_detection_alert_outbox set status = 'PUBLISHED', claim_token = null where alert_id = ?")) {
                statement.setString(1, locked.getAlertId());
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
        recover();
        assertEquals("PUBLISHED", load(locked).getStatus());
    }

    @Test
    void onlyOneConcurrentPublisherReceivesAClaim() throws Exception {
        var row = processing(0, false);
        recover();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> claim = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
                    return repository.claim(row.getAlertId(), "PENDING", 0, token(), Instant.now(), 2);
                }
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, load(row).getAttempts());
    }

    @Test
    void migrationPreservesExistingClaimsAndDeliveryHistory() {
        var before = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("claim_upgrade").locations("classpath:db/migration").target("23").load();
        before.migrate();
        jdbc.update("insert into claim_upgrade.t_detection_alert_outbox "
                + "(alert_id, tenant_id, payload, status, attempts, next_attempt_at, created_at, updated_at, delivered_at) "
                + "values ('upgrade-alert', ?, '{}', 'PROCESSING', 3, now(), now(), now(), now())", TENANT);

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("claim_upgrade").locations("classpath:db/migration").load().migrate();

        var stored = jdbc.queryForMap("select * from claim_upgrade.t_detection_alert_outbox where alert_id = 'upgrade-alert'");
        assertEquals("PROCESSING", stored.get("status"));
        assertEquals(3, stored.get("attempts"));
        assertEquals(TENANT, stored.get("tenant_id"));
        assertTrue(stored.get("delivered_at") != null);
        assertNull(stored.get("claim_token"));
    }
}
