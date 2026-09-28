package com.socp.incident.web.service;

import com.socp.platform.tenant.context.TenantContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentAggregationLockTest {

    @Test
    void serializesSameEntityAcrossTransactionsAndUsesBoundedShards() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:incident-lock;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE t_incident_merge_lock (
                  tenant_id VARCHAR(64) NOT NULL, shard_id INTEGER NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  PRIMARY KEY (tenant_id, shard_id))
                """);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        IncidentAggregationLock lock = new IncidentAggregationLock(jdbc, manager);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        CountDownLatch firstOwnsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondAcquired = new AtomicBoolean();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> TenantContext.runWith("tenant-a", () ->
                    transaction.executeWithoutResult(status -> {
                        lock.lock("host-17");
                        firstOwnsLock.countDown();
                        await(releaseFirst);
                    })));
            assertThat(firstOwnsLock.await(2, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> TenantContext.runWith("tenant-a", () ->
                    transaction.executeWithoutResult(status -> {
                        lock.lock("host-17");
                        secondAcquired.set(true);
                    })));

            Thread.sleep(100L);
            assertThat(secondAcquired).isFalse();
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(secondAcquired).isTrue();
        }

        assertThat(IncidentAggregationLock.shard("host-17"))
                .isBetween(0, IncidentAggregationLock.SHARDS - 1)
                .isEqualTo(IncidentAggregationLock.shard("HOST-17"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_incident_merge_lock", Integer.class))
                .isOne();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
