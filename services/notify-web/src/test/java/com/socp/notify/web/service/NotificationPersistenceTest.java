package com.socp.notify.web.service;

import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.repository.NotificationDispatchLogRepository;
import com.socp.notify.web.persistence.store.ChannelCoordinator;
import com.socp.notify.web.persistence.store.ChannelStore;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.error.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(showSql = false, properties = {"socp.demo-data.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@Import({NotificationDeliveryState.class, ChannelStore.class, ChannelCoordinator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(30)
class NotificationPersistenceTest {
    @Autowired NotificationDeliveryState state;
    @Autowired ChannelStore channels;
    @Autowired NotificationDispatchLogRepository logs;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        clear();
        TenantContext.set("tenant-a");
    }
    @AfterEach void cleanup() { TenantContext.clear(); clear(); }
    void clear() {
        jdbc.update("delete from t_notification_delivery");
        jdbc.update("delete from t_notification_dispatch_log");
        jdbc.update("delete from t_channel");
        jdbc.update("delete from t_notification_channel_namespace");
    }

    @Test void claimIsDurableAndSuccessSurvivesReplay() {
        var first = state.claim("alarm", "channel");
        assertNotNull(first.token());
        assertNull(state.claim("alarm", "channel").token());
        assertTrue(state.finish("alarm", "channel", first.token(), "{\"status\":\"sent\"}", true));
        assertEquals("{\"status\":\"sent\"}", state.claim("alarm", "channel").receiptJson());
        assertFalse(state.finish("alarm", "channel", first.token(), "failed", false));
    }

    @Test void expiredOwnerCannotWriteOverNewAttemptOrReceipt() {
        var first = state.claim("alarm", "channel");
        due("tenant-a");
        var second = state.claim("alarm", "channel");
        assertNotEquals(first.token(), second.token());
        assertFalse(state.finish("alarm", "channel", first.token(), "stale", true));
        assertTrue(state.finish("alarm", "channel", second.token(), "new", true));
        assertFalse(state.finish("alarm", "channel", first.token(), "stale failure", false));
        assertEquals("new", state.claim("alarm", "channel").receiptJson());
    }

    @Test void failureWaitsBeforeRetryAndIsNeverACompletedReceipt() {
        var first = state.claim("alarm", "channel");
        assertTrue(state.finish("alarm", "channel", first.token(), "{\"status\":\"failed\"}", false));
        var waiting = state.claim("alarm", "channel");
        assertNull(waiting.token());
        assertNull(waiting.receiptJson());
        due("tenant-a");
        assertNotNull(state.claim("alarm", "channel").token());
    }

    @Test void receiptPersistenceFailureRollsBackAndKeepsUnconfirmedClaim() {
        var first = state.claim("alarm", "channel");
        assertThrows(RuntimeException.class, () -> state.finish("alarm", "channel", first.token(), null, true));
        assertNull(state.claim("alarm", "channel").receiptJson());
        assertNull(state.claim("alarm", "channel").token());
        assertTrue(state.finish("alarm", "channel", first.token(), "confirmed", true));
    }

    @Test void sameAlarmAndChannelAreIndependentAcrossTenantsAndCallbacksAreFenced() {
        var a = state.claim("alarm", "channel");
        TenantContext.set("tenant-b");
        var b = state.claim("alarm", "channel");
        assertNotNull(b.token());
        assertFalse(state.finish("alarm", "channel", a.token(), "wrong tenant", true));
        assertTrue(state.finish("alarm", "channel", b.token(), "b", true));
        TenantContext.set("tenant-a");
        assertTrue(state.finish("alarm", "channel", a.token(), "a", true));
        assertEquals(2, jdbc.queryForObject("select count(*) from t_notification_delivery", Integer.class));
    }

    @org.junit.jupiter.api.RepeatedTest(5) void competingFirstClaimsHaveOneOwner() throws Exception {
        var owners = new AtomicInteger();
        race(() -> { if (state.claim("alarm", "channel").token() != null) owners.incrementAndGet(); });
        assertEquals(1, owners.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from t_notification_delivery", Integer.class));
    }

    @Test void concurrentDispatchesSendOnceAndOnlyAfterCommittedAdmission() throws Exception {
        var channel = channels.add(new Channel("webhook", "Ops", "WEBHOOK", "http://fixture.invalid", true, ""));
        var http = mock(SocpHttpClient.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(http.postExternalOnce(any(), any(), any(), anyInt())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("tenant-a", TenantContext.require());
            assertEquals(1, jdbc.queryForObject("select count(*) from t_notification_delivery where claim_token is not null", Integer.class));
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new ServiceCall(null, "fixture", true, 200, "ok", null, 1, false, 1);
        });
        var executor = new NotificationExecutor();
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatcher = new NotificationDispatcher(channels, http, state, logs, null, executor);
            var first = requests.submit(() -> TenantContext.callWith("tenant-a", () -> dispatcher.dispatch(Map.of("id", "alarm"))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = dispatcher.dispatch(Map.of("id", "alarm"));
            assertEquals(1, second.get("failed"));
            release.countDown();
            assertEquals(0, first.get(5, TimeUnit.SECONDS).get("failed"));
            assertEquals(0, dispatcher.dispatch(Map.of("id", "alarm")).get("failed"));
            verify(http, times(1)).postExternalOnce(any(), any(), any(), anyInt());
        } finally { release.countDown(); executor.close(); }
    }

    @Test void completedLegacyReceiptIsReadWithoutSendingAgain() {
        jdbc.update("insert into t_notification_delivery(id,tenant_id,alarm_id,channel_id,result_json,delivered_at) values (?,?,?,?,?,current_timestamp)",
                NotificationDeliveryState.deliveryId("tenant-a", "alarm", "channel"), "tenant-a", "alarm", "channel", "old result");
        assertEquals("old result", state.claim("alarm", "channel").receiptJson());
    }

    @Test void populatedV4UpgradePreservesReceiptsAndAllowsFullLengthTargets() {
        String schema = "upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String qualified = "\"" + schema + "\".";
        var source = jdbc.getDataSource();
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("4").load().migrate();
        jdbc.update("insert into " + qualified + "t_notification_delivery(id,tenant_id,alarm_id,channel_id,result_json,delivered_at) "
                + "values ('old','tenant-a','alarm','channel','legacy receipt',current_timestamp)");
        jdbc.update("insert into " + qualified + "t_channel(id,tenant_id,name,type,target,enabled) values ('channel','tenant-a','Ops','WEBHOOK','old',true)");
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        assertEquals(1, jdbc.queryForObject("select count(*) from " + qualified + "t_notification_delivery "
                + "where delivered_at is not null and claim_token is null and result_json = 'legacy receipt'", Integer.class));
        jdbc.update("update " + qualified + "t_channel set target = ?", "x".repeat(2048));
        assertEquals(2048, jdbc.queryForObject("select length(target) from " + qualified + "t_channel", Integer.class));
    }

    @Test void channelQuotaIsAtomicAcrossReplicas() throws Exception {
        for (int i = 0; i < ChannelStore.MAX_CHANNELS - 1; i++) channels.add(channel("ch-" + i, false));
        var admitted = new AtomicInteger();
        race(() -> {
            try { channels.add(channel(java.util.UUID.randomUUID().toString(), false)); admitted.incrementAndGet(); }
            catch (ApiException quota) { assertEquals(409, quota.getCode()); }
        });
        assertEquals(1, admitted.get());
        assertEquals(ChannelStore.MAX_CHANNELS, channels.list(1, 500).getTotalElements());
    }

    @Test void enablingQuotaIsAtomicAndDisablingRemainsAvailable() throws Exception {
        for (int i = 0; i < ChannelStore.MAX_ENABLED - 1; i++) channels.add(channel("ch-" + i, true));
        channels.add(channel("a", false)); channels.add(channel("b", false));
        var index = new AtomicInteger();
        var admitted = new AtomicInteger();
        race(() -> {
            try { channels.toggle(index.getAndIncrement() == 0 ? "a" : "b"); admitted.incrementAndGet(); }
            catch (ApiException quota) { assertEquals(409, quota.getCode()); }
        });
        assertEquals(1, admitted.get());
        assertEquals(ChannelStore.MAX_ENABLED, channels.enabled().size());
        assertFalse(channels.toggle("ch-0").enabled());
    }

    @Test void listIsPagedDeterministicallyAndTenantScoped() {
        for (int i = 0; i < 5; i++) channels.add(channel("ch-" + i, false));
        TenantContext.set("tenant-b");
        channels.add(channel("other", true));
        TenantContext.set("tenant-a");
        assertEquals(List.of("ch-2", "ch-3"), channels.list(2, 2).map(Channel::id).getContent());
        assertEquals(5, channels.list(2, 2).getTotalElements());
        assertNull(channels.get("other"));
        assertFalse(channels.delete("other"));
        assertThrows(ApiException.class, () -> channels.update(channel("other", false)));
    }

    @Test void newChannelCannotOverwriteAnotherTenantsIdentity() {
        channels.add(channel("same", false));
        TenantContext.set("tenant-b");
        assertThrows(RuntimeException.class, () -> channels.add(channel("same", true)));
        assertNull(channels.get("same"));
        TenantContext.set("tenant-a");
        assertFalse(channels.get("same").enabled());
    }

    @Test void deletionCannotBeResurrectedByAStaleEdit() {
        var original = channels.add(channel("delete", false));
        assertTrue(channels.delete(original.id()));
        assertThrows(ApiException.class, () -> channels.update(original));
        assertNull(channels.get(original.id()));
    }

    @Test void legacyOverQuotaConfigurationRemainsManageableButCannotSilentlyTruncateDispatch() {
        for (int i = 0; i < 17; i++) {
            jdbc.update("insert into t_channel(id,tenant_id,name,type,target,enabled) values (?,'tenant-a',?,'LOG','local',true)", "old-" + i, "old-" + i);
        }
        assertThrows(ApiException.class, channels::enabled);
        assertEquals(17, channels.list(1, 10).getTotalElements());
        assertFalse(channels.toggle("old-0").enabled());
        assertEquals(16, channels.enabled().size());
    }

    @Test void maximumApiTargetLengthRoundTripsThroughDatabase() {
        var longTarget = new Channel("long", "Long", "WEBHOOK", "https://example.invalid/" + "x".repeat(2024), false, "");
        channels.add(longTarget);
        assertEquals(longTarget.target(), channels.get("long").target());
    }

    private void due(String tenant) {
        jdbc.update("update t_notification_delivery set next_attempt_at = ? where tenant_id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), tenant);
    }
    private static Channel channel(String id, boolean enabled) { return new Channel(id, "Ops", "LOG", "local", enabled, ""); }
    private static void race(Runnable operation) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> TenantContext.runWith("tenant-a", () -> {
                ready.countDown();
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                operation.run();
            })));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
    }
}
