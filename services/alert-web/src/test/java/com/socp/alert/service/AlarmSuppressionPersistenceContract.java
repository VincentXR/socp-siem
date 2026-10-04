package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.persistence.repository.AlarmSuppressionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Spring transactions/repositories; the same contract runs on H2 and PostgreSQL. */
@DataJpaTest(showSql = false)
@Import({AlarmSuppressionService.class, AlarmSuppressionLock.class, AlarmDispositionService.class,
        AlarmFeedbackService.class, AlarmDeliveryRegistrar.class,
        AlarmService.class, AlarmQueryService.class, AlarmStatisticsService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AlarmSuppressionPersistenceContract {
    @Autowired AlarmSuppressionService suppression;
    @Autowired AlarmDispositionService disposition;
    @Autowired AlarmFeedbackService feedback;
    @Autowired AlarmDeliveryRegistrar registrar;
    @Autowired AlarmRepository alarms;
    @Autowired AlarmService alarmService;
    @org.springframework.boot.test.mock.mockito.MockBean AlarmEnrichmentService enrichment;
    @org.springframework.boot.test.mock.mockito.MockBean OutboxPublisher outbox;
    @org.springframework.boot.test.mock.mockito.MockBean AlarmDeliveryPublisher deliveries;
    @Autowired AlarmSuppressionRepository windows;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void explicitWindowSuppressesDeliveryAndReplayWithoutLosingDispositionState() {
        TenantContext.runWith(UUID.randomUUID().toString(), () -> {
            String tenant = TenantContext.require();
            suppression.record("rule", "host", "MANUAL", "scanner", null, "analyst", 3600L, false);
            assertThat(suppression.suppresses(tenant, "rule", "host")).isTrue();
            Alarm alarm = new Alarm("rule", "Rule", Severity.HIGH, "test", "host");
            alarm.setTenantId(tenant); alarm.setSourceAlertId(UUID.randomUUID().toString());
            Alarm saved = alarmService.create(alarm, List.of());
            assertThat(saved.getStatus()).isEqualTo("SUPPRESSED");
            String id = saved.getId();
            String payload = AlarmPayloadCodec.write(saved, List.of());
            registrar.register(tenant, id, payload);
            registrar.register(tenant, id, payload);
            assertThat(registrar.status(tenant, id)).extracting(row -> row.get("destination")).containsExactly("CLICKHOUSE");
            assertThat(disposition.get(id).status()).isEqualTo("SUPPRESSED");
            assertThat(disposition.assign(id, "alice").status()).isEqualTo("SUPPRESSED");
            assertThat(disposition.addNote(id, "alice", "checked", "note-1").status()).isEqualTo("SUPPRESSED");
            assertThatThrownBy(() -> disposition.setStatus(id, "OPEN")).isInstanceOf(ApiException.class);
            assertThat(disposition.setStatus(id, "INVESTIGATING").status()).isEqualTo("INVESTIGATING");
            // A later re-open/replay must not erase the admission-time suppression decision.
            registrar.register(tenant, id, payload);
            assertThat(registrar.status(tenant, id)).hasSize(1);
            suppression.release("rule", "host");
            assertThat(suppression.suppresses(tenant, "rule", "host")).isFalse();
            Alarm resumed = alarmService.create(new Alarm("rule", "Rule", Severity.HIGH, "resumed", "host"), List.of());
            assertThat(resumed.getStatus()).isEqualTo("OPEN");
            assertThat(registrar.status(tenant, resumed.getId())).hasSize(4);
        });
    }


    @Test
    void feedbackIsIndependentAndWindowCleanupCannotDeleteAnotherTenantsHistory() {
        String tenant = UUID.randomUUID().toString(), other = tenant + "-other";
        TenantContext.runWith(other, () -> suppression.record("other", "host", "MANUAL", "other", null, "a", 3600L, false));
        jdbc.update("UPDATE t_alarm_suppression SET expires_at=? WHERE tenant_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), other);
        TenantContext.runWith(tenant, () -> {
            Instant expiry = Instant.now().plusSeconds(3600);
            feedback.save("alarm-with-no-entity", "FALSE_POSITIVE", "one event", expiry, "analyst");
            assertThat(windows.countByTenantId(tenant)).isZero();
            suppression.record("mine", "host", "MANUAL", "mine", null, "analyst", 3600L, false);
            assertThat(suppression.list()).hasSize(1);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_alarm_suppression WHERE tenant_id=?", Integer.class, other)).isEqualTo(1);
    }

    @Test
    void concurrentSameScopeUpsertsAndFinalQuotaSlotAreSerialized() throws Exception {
        String tenant = UUID.randomUUID().toString();
        assertThat(concurrent(tenant, "same", "same")).containsExactlyInAnyOrder(200, 200);
        TenantContext.runWith(tenant, () -> assertThat(windows.countByTenantId(tenant)).isEqualTo(1));
        // Seed 998 additional scopes without 998 separate transactions.
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            for (int i = 0; i < 998; i++) jdbc.update(
                    "INSERT INTO t_alarm_suppression (id,tenant_id,rule_id,entity_key,origin,reason,expires_at) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), tenant, "seed-" + i, "host", "MANUAL", "seed",
                    java.sql.Timestamp.from(Instant.now().plusSeconds(7200)));
        });
        assertThat(concurrent(tenant, "last-a", "last-b")).containsExactlyInAnyOrder(200, 409);
        TenantContext.runWith(tenant, () -> {
            assertThat(windows.countByTenantId(tenant)).isEqualTo(1000);
            suppression.record("same", "host", "MANUAL", "renew", null, "analyst", 7200L, false);
            assertThat(windows.countByTenantId(tenant)).isEqualTo(1000);
        });
    }

    @Test
    void invalidLaterItemRollsBackTheWholeBatchThroughTheRealServiceProxy() {
        TenantContext.runWith(UUID.randomUUID().toString(), () -> {
            Alarm one = alarmService.create(new Alarm("rule", "Rule", Severity.HIGH, "one", "host"), List.of());
            Alarm two = alarmService.create(new Alarm("rule", "Rule", Severity.HIGH, "two", "host"), List.of());
            var ids = java.util.stream.Stream.of(one.getId(), two.getId()).sorted().toList();
            disposition.setStatus(ids.get(0), "INVESTIGATING");
            disposition.setStatus(ids.get(1), "CLOSED");
            var before = disposition.get(ids.get(0));
            assertThatThrownBy(() -> disposition.batchUpdate(ids, "OPEN", null, null)).isInstanceOf(ApiException.class);
            assertThat(disposition.get(ids.get(0))).isEqualTo(before);
            assertThat(disposition.get(ids.get(1)).status()).isEqualTo("CLOSED");
        });
    }

    @Test
    void concurrentFirstDispositionWritesPreserveSuppressionOwnerAndNote() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String id = TenantContext.callWith(tenant, () -> {
            suppression.record("rule", "host", "MANUAL", "scanner", null, "analyst", 3600L, false);
            return alarmService.create(new Alarm("rule", "Rule", Severity.HIGH, "one", "host"), List.of()).getId();
        });
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> { start.await(); return TenantContext.callWith(tenant, () -> disposition.assign(id, "alice")); });
            var note = pool.submit(() -> { start.await(); return TenantContext.callWith(tenant, () -> disposition.addNote(id, "bob", "evidence", "key")); });
            start.countDown();
            assertThat(owner.get(20, TimeUnit.SECONDS).status()).isEqualTo("SUPPRESSED");
            assertThat(note.get(20, TimeUnit.SECONDS).status()).isEqualTo("SUPPRESSED");
        }
        TenantContext.runWith(tenant, () -> {
            var result = disposition.get(id);
            assertThat(result.status()).isEqualTo("SUPPRESSED");
            assertThat(result.assignee()).isEqualTo("alice");
            assertThat(result.notes()).filteredOn(n -> "evidence".equals(n.content())).hasSize(1);
            assertThat(result.allowedTransitions()).containsExactly("INVESTIGATING", "CLOSED", "SUPPRESSED");
        });
    }

    private List<Integer> concurrent(String tenant, String first, String second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> write(tenant, first, start));
            var b = pool.submit(() -> write(tenant, second, start));
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private int write(String tenant, String rule, CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrency start timed out");
        return TenantContext.callWith(tenant, () -> {
            try { suppression.record(rule, "host", "MANUAL", "test", null, "analyst", 3600L, false); return 200; }
            catch (ApiException failure) { return failure.getCode(); }
        });
    }
}
