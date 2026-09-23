package com.socp.detect.web.routing;

import com.socp.platform.test.MiddlewareImages;
import com.socp.platform.tenant.context.TenantContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DetectionRouteOutboxPostgresTest extends DetectionRouteOutboxPublisherPersistenceTest {
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

    // The batch limit depends on PostgreSQL's locking CTE semantics; H2 does
    // not model the same UPDATE behavior. Keep these assertions on the production dialect.
    @Test
    void recoveryDrainsInBoundedBatches() {
        for (int i = 0; i < 101; i++) processing(1);

        publisher.recoverStale();

        assertEquals(100, countByStatus("PENDING"));
        assertEquals(1, countByStatus("PROCESSING"));
        publisher.recoverStale();
        assertEquals(101, countByStatus("PENDING"));
    }

    @Test
    void exhaustedPendingRowsAlsoRetireInBoundedBatches() {
        for (int i = 0; i < 101; i++) {
            var row = processing(2);
            row.setStatus("PENDING");
            repository.saveAndFlush(row);
        }

        publisher.recoverStale();

        assertEquals(100, countByStatus("DEAD"));
        assertEquals(1, countByStatus("PENDING"));
        publisher.recoverStale();
        assertEquals(101, countByStatus("DEAD"));
    }

    private long countByStatus(String status) {
        return jdbc.queryForObject("select count(*) from t_detection_route_outbox "
                + "where tenant_id = ? and status = ?", Long.class, "route-test", status);
    }

    @Test
    void recoverySkipsLockedRowsAndDoesNotUndoAConcurrentCompletion() throws Exception {
        var locked = processing(1);
        var available = processing(1);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "select delivery_id from t_detection_route_outbox where delivery_id = ? for update")) {
                statement.setString(1, locked.getDeliveryId());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }

            Instant now = Instant.now();
            assertEquals(1, repository.recoverStaleBatch(now.minusSeconds(120), now, 2, 100));
            assertEquals("PENDING", load(available).getStatus());
            assertEquals("PROCESSING", load(locked).getStatus());
            try (var statement = connection.prepareStatement(
                    "update t_detection_route_outbox set status = 'PUBLISHED', delivery_offset = 77 "
                            + "where delivery_id = ?")) {
                statement.setString(1, locked.getDeliveryId());
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
        assertEquals(0, repository.recoverStaleBatch(Instant.now().minusSeconds(120), Instant.now(), 2, 100));
        assertEquals("PUBLISHED", load(locked).getStatus());
        assertEquals(77L, load(locked).getDeliveryOffset());
    }

    @Test
    void twoPublishersCannotOwnTheSameAttempt() throws Exception {
        var row = processing(0);
        repository.recoverStaleBatch(Instant.now().minusSeconds(120), Instant.now(), 2, 100);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> claim = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try (TenantContext.Scope ignored = TenantContext.open("route-test")) {
                    return repository.claim(row.getDeliveryId(), Instant.now(), 0, 2);
                }
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, load(row).getAttempts());
    }
}
