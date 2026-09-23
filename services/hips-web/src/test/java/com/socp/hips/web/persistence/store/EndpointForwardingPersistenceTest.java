package com.socp.hips.web.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.service.EndpointEventDelivery;
import com.socp.hips.web.service.EndpointForwardingPublisher;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EndpointStore.class, EndpointEventStore.class, EndpointForwardingStore.class, EndpointHistoryRetentionStore.class,
        EndpointEventDelivery.class, ObjectMapper.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:endpoint-forwarding;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "socp.demo-data.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "socp.hips.forwarding.max-pending=3", "socp.hips.forwarding.max-tenant-pending=2", "socp.hips.forwarding.max-attempts=2"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EndpointForwardingPersistenceTest {
    @Autowired EndpointForwardingStore outbox;
    @Autowired EndpointEventDelivery delivery;
    @Autowired EndpointEventStore history;
    @Autowired EndpointHistoryRetentionStore retention;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean EndpointForwardingPublisher publisher;

    @BeforeEach void reset() {
        TenantContext.clear();
        jdbc.update("delete from t_endpoint_forwarding");
        jdbc.update("delete from t_endpoint_event");
        jdbc.update("delete from t_endpoint");
    }

    @org.junit.jupiter.api.AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void historyRetentionIsBoundedAndProtectsEveryReceiptStateUntilPruned() {
        var rows = new java.util.ArrayList<Object[]>();
        for (int i = 0; i < 1005; i++) rows.add(new Object[]{"old-" + i, i % 2 == 0 ? "tenant-a" : "tenant-b", Timestamp.from(Instant.EPOCH)});
        jdbc.batchUpdate("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values(?,?,?,'{}')", rows);
        jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values('recent','tenant-a',?,'{}')", Timestamp.from(Instant.now()));
        for (String state : java.util.List.of("PENDING", "PROCESSING", "DEAD", "DELIVERED")) {
            jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values(?, 'tenant-a',?,'{}')", state, Timestamp.from(Instant.EPOCH));
            jdbc.update("""
                    insert into t_endpoint_forwarding(event_id,tenant_id,payload_json,status,next_attempt_at,created_at,delivered_at)
                    values(?,'tenant-a','{}',?,?,?,?)
                    """, state, state, Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH));
        }
        try (var ignored = TenantContext.openSystem()) {
            assertThat(retention.oldestEligible(Instant.EPOCH.plusSeconds(1))).contains(Instant.EPOCH);
            assertThat(retention.prune(Instant.EPOCH.plusSeconds(1), 1000)).isEqualTo(1000);
            assertThat(retention.prune(Instant.EPOCH.plusSeconds(1), 1000)).isEqualTo(5);
            assertThat(retention.prune(Instant.EPOCH.plusSeconds(1), 1000)).isZero();
            assertThat(retention.oldestEligible(Instant.EPOCH.plusSeconds(1))).isEmpty();
            assertThat(outbox.prune(Instant.EPOCH.plusSeconds(1))).isEqualTo(1);
            assertThat(retention.prune(Instant.EPOCH.plusSeconds(1), 1000)).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("select event_id from t_endpoint_event", String.class))
                .containsExactlyInAnyOrder("recent", "PENDING", "PROCESSING", "DEAD");
    }

    @Test void historyRetentionRejectsUnscopedCallsAndRollsBackDeletion() {
        jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values('old','tenant-a',?,'{}')", Timestamp.from(Instant.EPOCH));
        assertThatThrownBy(() -> retention.prune(Instant.now(), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.callWith("tenant-a", () -> retention.oldestEligible(Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        try (var ignored = TenantContext.openSystem()) {
            assertThatThrownBy(() -> retention.prune(Instant.now(), 1001)).isInstanceOf(IllegalArgumentException.class);
            assertThat(retention.prune(Instant.EPOCH, 1)).isZero(); // Cutoff is exclusive.
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(retention.prune(Instant.EPOCH.plusSeconds(1), 1)).isEqualTo(1);
                throw new IllegalStateException("fixture rollback");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isEqualTo(1);
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test void historyRetentionSkipsLockedHistoryAndYieldsToAdmission() throws Exception {
        for (String id : java.util.List.of("old-1", "old-2"))
            jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values(?,'tenant-a',?,'{}')", id, Timestamp.from(Instant.EPOCH));
        for (String lockSql : java.util.List.of("select event_id from t_endpoint_event where event_id='old-1' for update",
                "select id from t_endpoint_forwarding_admission where id=1 for update")) {
            var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var owner = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    jdbc.queryForList(lockSql); locked.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock fixture timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    return null;
                }));
                try {
                    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                    var cleanup = pool.submit(() -> {
                        try (var ignored = TenantContext.openSystem()) { return retention.prune(Instant.EPOCH.plusSeconds(1), 10); }
                    });
                    assertThat(cleanup.get(3, TimeUnit.SECONDS)).isEqualTo(lockSql.contains("admission") ? 0 : 1);
                } finally { release.countDown(); }
                owner.get(5, TimeUnit.SECONDS);
            }
        }
        try (var ignored = TenantContext.openSystem()) { assertThat(retention.prune(Instant.EPOCH.plusSeconds(1), 10)).isEqualTo(1); }
    }

    @Test void keyedReplayPreservesHistoryHeartbeatAndDeadReceiptEvenWhenAdmissionIsFull() {
        jdbc.update("""
                insert into t_endpoint(id,endpoint_id,tenant_id,hostname,last_heartbeat)
                values('keyed-registry','keyed-endpoint','tenant-a','web-01',?)
                """, Timestamp.from(Instant.EPOCH));
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("hostname", "web-01"); input.put("fields", Map.of("user.name", "alice", "proc.pid", 1));
        var first = keyed("tenant-a", "collector:one", "request-1", input);
        accept("tenant-a"); // Tenant queue is now full, but replay is not new admission.
        jdbc.update("update t_endpoint set last_heartbeat=? where id='keyed-registry'", Timestamp.from(Instant.EPOCH));
        jdbc.update("update t_endpoint_forwarding set status='DEAD',attempts=2 where event_id=?", first.get("eventId"));
        Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("fields", Map.of("proc.pid", 1, "user.name", "alice")); reordered.put("hostname", "web-01");
        assertThat(keyed("tenant-a", "collector:one", "request-1", reordered)).isEqualTo(first);
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select last_heartbeat from t_endpoint where id='keyed-registry'", Timestamp.class))
                .isEqualTo(Timestamp.from(Instant.EPOCH));
        assertThat(outbox.status("tenant-a", (String) first.get("eventId"))).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select attempts from t_endpoint_forwarding where event_id=?", Integer.class, first.get("eventId"))).isEqualTo(2);
    }

    @Test void sameKeyDifferentContentConflictsAndProducerAndTenantScopesDoNotMerge() {
        var first = keyed("tenant-a", "collector:one", "same-key", Map.of("hostname", "one"));
        assertThatThrownBy(() -> keyed("tenant-a", "collector:one", "same-key", Map.of("hostname", "two")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        var otherProducer = keyed("tenant-a", "service:one", "same-key", Map.of("hostname", "one"));
        var otherTenant = keyed("tenant-b", "collector:one", "same-key", Map.of("hostname", "one"));
        assertThat(java.util.List.of(first.get("eventId"), otherProducer.get("eventId"), otherTenant.get("eventId")))
                .doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select request_key_hash from t_endpoint_forwarding where event_id=?", String.class, first.get("eventId")))
                .hasSize(64).doesNotContain("same-key");
    }

    @Test void concurrentKeyedRequestsAcrossIndependentServicesCreateOneEvent() throws Exception {
        var proxy = new org.springframework.aop.framework.ProxyFactory(
                new EndpointEventDelivery(history, outbox, publisher, new ObjectMapper(), 262144));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var other = (EndpointEventDelivery) proxy.getProxy();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
            for (int i = 0; i < 8; i++) {
                var writer = i % 2 == 0 ? delivery : other;
                futures.add(pool.submit(() -> {
                    start.await();
                    return TenantContext.callWith("tenant-a", () -> writer.accept(Map.of("hostname", "host"), "collector:one", "parallel"));
                }));
            }
            start.countDown();
            var ids = new java.util.HashSet<Object>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS).get("eventId"));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isEqualTo(1);
    }

    @Test void rolledBackKeyCanBeRetriedAndInvalidKeysDoNotWrite() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            keyed("tenant-a", "collector:one", "rollback", Map.of("hostname", "host"));
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isZero();
        for (String key : java.util.List.of("", " ", "with space", "x".repeat(257), "\u00e9")) {
            assertThatThrownBy(() -> keyed("tenant-a", "collector:one", key, Map.of("hostname", "host")))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> keyed("tenant-a", null, "valid", Map.of("hostname", "host")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(403));
        keyed("tenant-a", "collector:one", "rollback", Map.of("hostname", "host"));
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isEqualTo(1);
    }

    @Test void retentionOnlyExpiresDeliveredRequestKeysAndDoesNotReplayLegacyHistory() {
        var first = keyed("tenant-a", "collector:one", "retained", Map.of("hostname", "host"));
        String id = (String) first.get("eventId");
        jdbc.update("update t_endpoint_forwarding set created_at=? where event_id=?", Timestamp.from(Instant.EPOCH), id);
        assertThat(outbox.prune(Instant.now())).isZero();
        jdbc.update("update t_endpoint_forwarding set status='DELIVERED',delivered_at=? where event_id=?", Timestamp.from(Instant.EPOCH), id);
        assertThat(keyed("tenant-a", "collector:one", "retained", Map.of("hostname", "host"))).isEqualTo(first);
        assertThat(outbox.prune(Instant.now())).isEqualTo(1);
        var afterExpiry = keyed("tenant-a", "collector:one", "retained", Map.of("hostname", "host"));
        assertThat(afterExpiry.get("eventId")).isNotEqualTo(id);
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(2);
    }

    @Test void corruptReplayFailsClosedWithoutCreatingAnotherEvent() {
        var first = keyed("tenant-a", "collector:one", "corrupt", Map.of("hostname", "host"));
        for (String corrupted : java.util.List.of("{}", "{", "null", "{\"tenantId\":\"tenant-b\"}")) {
            jdbc.update("update t_endpoint_forwarding set payload_json=? where event_id=?", corrupted, first.get("eventId"));
            assertThatThrownBy(() -> keyed("tenant-a", "collector:one", "corrupt", Map.of("hostname", "host")))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(1);
    }

    @Test void v4UpgradePreservesReceiptsAndEnforcesCompleteUniqueProducerKeys() {
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas("hips_request_upgrade").defaultSchema("hips_request_upgrade")
                .locations("classpath:db/migration").target("4").load().migrate();
        jdbc.update("""
                insert into "hips_request_upgrade".t_endpoint_forwarding(event_id,tenant_id,payload_json,status,next_attempt_at,created_at)
                values('old-1','tenant-a','{}','DEAD',?,?),('old-2','tenant-a','{}','DELIVERED',?,?)
                """, Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH));
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas("hips_request_upgrade").defaultSchema("hips_request_upgrade")
                .locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from \"hips_request_upgrade\".t_endpoint_forwarding where producer_hash is null", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("update \"hips_request_upgrade\".t_endpoint_forwarding set producer_hash='p' where event_id='old-1'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update \"hips_request_upgrade\".t_endpoint_forwarding set producer_hash='p',request_key_hash='k',request_fingerprint='f' where event_id='old-1'");
        assertThatThrownBy(() -> jdbc.update("update \"hips_request_upgrade\".t_endpoint_forwarding set producer_hash='p',request_key_hash='k',request_fingerprint='f' where event_id='old-2'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Map<String, Object> keyed(String tenant, String producer, String key, Map<String, Object> input) {
        return TenantContext.callWith(tenant, () -> delivery.accept(input, producer, key));
    }

    @Test void escapedPayloadByteLimitRollsBackHistoryAndIntent() {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 15; i++) fields.put("key-" + i, "\u0001".repeat(4096));
        // Valid per-field/count/character limits can still exceed the byte limit after JSON escaping.
        assertThatThrownBy(() -> TenantContext.callWith("tenant-a", () ->
                delivery.accept(Map.of("hostname", "host", "output_fields", fields), "collector:one", "oversized")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(413));
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isZero();
        keyed("tenant-a", "collector:one", "oversized", Map.of("hostname", "host"));
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isEqualTo(1);
    }

    @Test void acceptsHistoryAndIntentAtomicallyWithTrustedTenant() {
        var event = accept("tenant-a");
        String id = (String) event.get("eventId");
        assertThat(outbox.status("tenant-a", id)).isEqualTo("PENDING");
        assertThat(outbox.status("tenant-b", id)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select payload_json from t_endpoint_forwarding where event_id=?", String.class, id))
                .contains("\"tenantId\":\"tenant-a\"").doesNotContain("spoofed");
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(1);
        var manual = TenantContext.callWith("tenant-a", () -> history.add(Map.of("hostname", "manual")));
        assertThat(outbox.status("tenant-a", (String) manual.get("eventId"))).isEqualTo("UNKNOWN");
    }

    @Test void upgradePreservesHistoryWithoutInventingDeliveryReceipts() {
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas("hips_forwarding_upgrade").defaultSchema("hips_forwarding_upgrade")
                .locations("classpath:db/migration").target("3").load().migrate();
        jdbc.update("""
                insert into "hips_forwarding_upgrade".t_endpoint_event(event_id,tenant_id,hostname,received_at,payload_json)
                values('legacy-event','tenant-a','host',?,'{}')
                """, Timestamp.from(Instant.EPOCH));
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas("hips_forwarding_upgrade").defaultSchema("hips_forwarding_upgrade")
                .locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from \"hips_forwarding_upgrade\".t_endpoint_event", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from \"hips_forwarding_upgrade\".t_endpoint_forwarding", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select id from \"hips_forwarding_upgrade\".t_endpoint_forwarding_admission", Integer.class)).isEqualTo(1);
    }

    @Test void outerRollbackDoesNotLeaveEitherHistoryOrIntent() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            accept("tenant-a");
            throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isZero();
    }

    @Test void tenantAndGlobalAdmissionFailuresRollBackHistory() {
        jdbc.update("""
                insert into t_endpoint(id,endpoint_id,tenant_id,hostname,last_heartbeat)
                values('registry-id','endpoint-id','tenant-a','web-01',?)
                """, Timestamp.from(Instant.EPOCH));
        accept("tenant-a"); accept("tenant-a");
        var heartbeat = jdbc.queryForObject("select last_heartbeat from t_endpoint where id='registry-id'", Timestamp.class);
        assertThat(heartbeat.toInstant()).isAfter(Instant.EPOCH);
        assertThatThrownBy(() -> accept("tenant-a")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select last_heartbeat from t_endpoint where id='registry-id'", Timestamp.class)).isEqualTo(heartbeat);
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(2);
        accept("tenant-b");
        assertThatThrownBy(() -> accept("tenant-c")).isInstanceOf(ResponseStatusException.class);
        assertThat(TenantContext.callWith("tenant-c", history::count)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isEqualTo(3);
    }

    @Test void concurrentAdmissionCannotOverrunTenantLimit() throws Exception {
        accept("tenant-a");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { accept("tenant-a"); return true; }
                catch (ResponseStatusException full) { return false; }
            };
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(2);
    }

    @Test void concurrentClaimsHaveOneOwnerAndExpiredCallbacksAreFenced() throws Exception {
        String id = (String) accept("tenant-a").get("eventId");
        Instant now = Instant.now().plusSeconds(1);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = (java.util.concurrent.Callable<Optional<EndpointForwardingStore.Claim>>) () -> {
                start.await(); return outbox.claim("tenant-a", id, now);
            };
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            var claims = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .flatMap(Optional::stream).toList();
            assertThat(claims).hasSize(1);
            var old = claims.getFirst();
            assertThat(outbox.claim("tenant-b", id, now.plusSeconds(121))).isEmpty();
            var fresh = outbox.claim("tenant-a", id, now.plusSeconds(121)).orElseThrow();
            assertThat(fresh.token()).isNotEqualTo(old.token());
            assertThat(outbox.complete(old, now)).isFalse();
            assertThat(outbox.fail(old, now, "stale failure")).isFalse();
            assertThat(outbox.complete(fresh, now.plusSeconds(122))).isTrue();
            assertThat(outbox.status("tenant-a", id)).isEqualTo("DELIVERED");
        }
    }

    @Test void lockedHeadDoesNotBlockAnotherReadyReceipt() throws Exception {
        String first = (String) accept("tenant-a").get("eventId");
        String second = (String) accept("tenant-a").get("eventId");
        jdbc.update("update t_endpoint_forwarding set next_attempt_at=? where event_id=?", Timestamp.from(Instant.EPOCH), first);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("select event_id from t_endpoint_forwarding where event_id=? for update", String.class, first);
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var next = pool.submit(() -> outbox.claim(null, null, Instant.now().plusSeconds(1))).get(5, TimeUnit.SECONDS);
                assertThat(next.orElseThrow().eventId()).isEqualTo(second);
            } finally { release.countDown(); }
            owner.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void restartRecoveryExhaustsBudgetAndRequeueNeverReusesOwnership() {
        String id = (String) accept("tenant-a").get("eventId");
        Instant now = Instant.now().plusSeconds(1);
        var first = outbox.claim("tenant-a", id, now).orElseThrow();
        var restarted = new EndpointForwardingStore(jdbc, 3, 2, 2);
        var second = new TransactionTemplate(transactions).execute(status ->
                restarted.claim("tenant-a", id, now.plusSeconds(121)).orElseThrow());
        assertThat(second.payload()).isEqualTo(first.payload());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(outbox.claim("tenant-a", id, now.plusSeconds(242))).isEmpty();
        assertThat(outbox.status("tenant-a", id)).isEqualTo("DEAD");
        assertThat(outbox.requeue("tenant-b", id, now)).isFalse();
        assertThat(outbox.requeue("tenant-a", id, now)).isTrue();
        var fresh = outbox.claim("tenant-a", id, now.plusSeconds(1)).orElseThrow();
        assertThat(fresh.attempts()).isEqualTo(1);
        assertThat(fresh.token()).isNotIn(first.token(), second.token());
        assertThat(outbox.complete(second, now)).isFalse();
        assertThat(outbox.fail(first, now, "late")).isFalse();
        assertThat(outbox.complete(fresh, now)).isTrue();
    }

    @Test void failureBackoffAndListingAreTenantScopedAndBounded() {
        String id = (String) accept("tenant-a").get("eventId");
        Instant now = Instant.now().plusSeconds(1);
        var first = outbox.claim("tenant-a", id, now).orElseThrow();
        assertThat(outbox.fail(first, now, "temporary")).isTrue();
        assertThat(outbox.claim("tenant-a", id, now.plusSeconds(1))).isEmpty();
        var second = outbox.claim("tenant-a", id, now.plusSeconds(3)).orElseThrow();
        assertThat(outbox.fail(second, now, "x".repeat(300))).isTrue();
        assertThat(outbox.list("tenant-b", "DEAD", 1)).isEmpty();
        assertThat(outbox.list("tenant-a", "DEAD", 1)).hasSize(1).first()
                .satisfies(row -> assertThat((String) row.get("lastError")).hasSize(256));
    }

    @Test void deliveredRetentionDeletesOnlyBoundedExpiredReceipts() {
        for (int i = 0; i < 115; i++) jdbc.update("""
                insert into t_endpoint_forwarding(event_id,tenant_id,payload_json,status,attempts,next_attempt_at,created_at,delivered_at)
                values(?,?,'{}','DELIVERED',1,?,?,?)
                """, "old-" + i, "tenant-a", Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH));
        accept("tenant-a");
        assertThat(outbox.prune(Instant.EPOCH.plusSeconds(1))).isEqualTo(100);
        assertThat(outbox.prune(Instant.EPOCH.plusSeconds(1))).isEqualTo(15);
        assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding where status='PENDING'", Long.class)).isEqualTo(1);
    }

    @Test void anotherPublisherRecoversFailedTransportWithTheSamePayloadAndIdentity() {
        var http = org.mockito.Mockito.mock(com.socp.platform.client.http.SocpHttpClient.class);
        var capturedBodies = new java.util.ArrayList<String>();
        var capturedKeys = new java.util.ArrayList<Map<String, String>>();
        org.mockito.Mockito.when(http.post(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call -> {
            assertThat(TenantContext.require()).isEqualTo("tenant-a");
            capturedBodies.add(call.getArgument(2)); capturedKeys.add(call.getArgument(5));
            boolean success = capturedBodies.size() > 1;
            return new com.socp.platform.client.http.ServiceCall(com.socp.platform.client.service.SocpService.SEARCH,
                    "http://search", success, success ? 200 : 503,
                    success ? "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}}" : "unavailable",
                    null, 1, false, 1);
        });
        String id = (String) accept("tenant-a").get("eventId");
        var initial = new EndpointForwardingPublisher(outbox, http, new ObjectMapper(), true);
        // Exercise transport failure, independently of sub-microsecond DB timestamp rounding.
        jdbc.update("update t_endpoint_forwarding set next_attempt_at=? where event_id=?", Timestamp.from(Instant.EPOCH), id);
        TenantContext.runWith("caller-tenant", () -> {
            assertThat(initial.forward("tenant-a", id)).isFalse();
            assertThat(TenantContext.require()).isEqualTo("caller-tenant");
        });
        assertThat(outbox.status("tenant-a", id)).isEqualTo("PENDING");
        assertThat(capturedBodies).hasSize(1);
        jdbc.update("update t_endpoint_forwarding set next_attempt_at=? where event_id=?", Timestamp.from(Instant.EPOCH), id);
        new EndpointForwardingPublisher(outbox, http, new ObjectMapper(), true).drain();
        assertThat(outbox.status("tenant-a", id)).isEqualTo("DELIVERED");
        assertThat(capturedBodies).hasSize(2).allMatch(capturedBodies.getFirst()::equals);
        assertThat(capturedKeys).containsExactly(Map.of("Idempotency-Key", "hips:" + id), Map.of("Idempotency-Key", "hips:" + id));
        assertThat(TenantContext.callWith("tenant-a", history::count)).isEqualTo(1);
    }

    private Map<String, Object> accept(String tenant) {
        return TenantContext.callWith(tenant, () -> delivery.accept(Map.of("hostname", "web-01", "tenantId", "spoofed")));
    }

    @Test void nativeFalcoFixtureSurvivesTheDurableHistoryAndForwardingBoundary() throws Exception {
        var mapper = new ObjectMapper();
        com.socp.hips.web.api.request.EndpointEventRequest input;
        try (var fixture = getClass().getResourceAsStream("/fixtures/falco-native-event.json")) {
            assertThat(fixture).isNotNull();
            input = mapper.readValue(fixture, com.socp.hips.web.api.request.EndpointEventRequest.class);
        }
        var event = TenantContext.callWith("tenant-a", () -> delivery.accept(input.asMap()));
        var claim = outbox.claim("tenant-a", (String) event.get("eventId"), Instant.now().plusSeconds(1)).orElseThrow();
        var persisted = mapper.readTree(claim.payload());
        assertThat(persisted.path("output_fields")).isEqualTo(mapper.valueToTree(input.outputFields()));
        assertThat(persisted.path("time").asText()).isEqualTo(input.time());
        assertThat(persisted.path("timestamp").asText()).isEqualTo(input.time());
        assertThat(persisted.path("source").asText()).isEqualTo("syscall");
        assertThat(persisted.path("tenantId").asText()).isEqualTo("tenant-a");
        String historyJson = jdbc.queryForObject("select payload_json from t_endpoint_event where event_id=?", String.class, event.get("eventId"));
        assertThat(mapper.readTree(historyJson)).isEqualTo(persisted);
    }
}
