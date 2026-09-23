package com.socp.detect.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.aopalliance.intercept.MethodInterceptor;
import com.socp.detect.web.persistence.repository.EntityRiskProfileRepository;
import com.socp.detect.web.persistence.repository.EntityRiskAlertRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({EntityRiskStore.class, EntityRiskCounterStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EntityRiskCounterPersistenceTest {
    @Autowired EntityRiskStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityRiskProfileRepository profiles;
    @Autowired EntityRiskAlertRepository alerts;
    @Autowired EntityRiskCounterStore counters;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void cleanup() {
        jdbc.update("delete from t_entity_risk_alert where tenant_id like 'counter-%'");
        jdbc.update("delete from t_entity_risk_profile where tenant_id like 'counter-%'");
    }
    @AfterEach void clearTenant() { cleanup(); TenantContext.clear(); }

    @Test void growingRuleNamesDoNotBlockDurableAlertProjection() {
        TenantContext.set("counter-growth");
        for (int i = 0; i < 100; i++) {
            store.recordForAlert("alert-" + i, "host-a", Severity.HIGH, "T1110",
                    "rule-" + i, "rule-%03d-".formatted(i) + "x".repeat(120), 0);
        }
        assertThat(jdbc.queryForObject("select alert_count from t_entity_risk_profile where tenant_id='counter-growth'", Long.class))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_alert where tenant_id='counter-growth'", Long.class))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_counter where tenant_id='counter-growth'", Long.class))
                .isEqualTo(101L);
        assertThat(jdbc.queryForObject("select sum(hit_count) from t_entity_risk_counter where tenant_id='counter-growth' and dimension='RULE'", Long.class))
                .isEqualTo(100L);
        var detail = store.get("host-a");
        assertThat((List<?>) detail.get("topRules")).hasSize(5);
        assertThat((List<?>) detail.get("mitre")).isEqualTo(List.of(Map.of("technique", "T1110", "count", 100L)));
        store.recordForAlert("alert-99", "host-a", Severity.HIGH, "T1110", "ignored", "ignored", 0);
        assertThat(jdbc.queryForObject("select alert_count from t_entity_risk_profile where tenant_id='counter-growth'", Long.class))
                .isEqualTo(100L);
        assertThatThrownBy(() -> jdbc.update("update t_entity_risk_profile set rules_json='{} ' where tenant_id='counter-growth'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void preservesDistinctControlAndUnicodeRuleNames() {
        TenantContext.set("counter-keys");
        List<String> names = List.of("nul" + (char) 0, "nul\\u0000", "quote\"\\", "\u89c4\u5219", "surrogate" + (char) 0xd800);
        for (int i = 0; i < names.size(); i++) {
            store.recordForAlert("key-" + i, "host-a", Severity.HIGH, null, "r", names.get(i), 0);
            store.recordForAlert("key-repeat-" + i, "host-a", Severity.HIGH, null, "r", names.get(i), 0);
        }
        var rows = (List<Map<String, Object>>) store.get("host-a").get("topRules");
        assertThat(rows).extracting(row -> row.get("rule")).containsExactlyInAnyOrderElementsOf(names);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("count")).isEqualTo(2L));
    }

    @Test void convertsLegacyCountsAtomicallyAndSurvivesIntegerRange() {
        TenantContext.set("counter-legacy");
        seedLegacy("counter-legacy", "host-a", "{\"T1110\":2}", "{\"Legacy rule\":2147483647}", 10);
        assertThat((List<?>) store.get("host-a").get("topRules"))
                .isEqualTo(List.of(Map.of("rule", "Legacy rule", "count", 2147483647L)));
        assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_counter where tenant_id='counter-legacy'", Long.class)).isZero();
        jdbc.execute("alter table t_entity_risk_counter add constraint counter_fixture_cap check (hit_count<2147483648)");
        try {
            assertThatThrownBy(() -> store.recordForAlert("retry", "host-a", Severity.HIGH, "T1110", "rule", "Legacy rule", 0))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("select alert_count from t_entity_risk_profile where tenant_id='counter-legacy'", Long.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("select counters_migrated from t_entity_risk_profile where tenant_id='counter-legacy'", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_counter where tenant_id='counter-legacy'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_alert where tenant_id='counter-legacy'", Long.class)).isZero();
        } finally { jdbc.execute("alter table t_entity_risk_counter drop constraint counter_fixture_cap"); }
        store.recordForAlert("retry", "host-a", Severity.HIGH, "T1110", "rule", "Legacy rule", 0);
        assertThat((List<?>) store.get("host-a").get("topRules"))
                .isEqualTo(List.of(Map.of("rule", "Legacy rule", "count", 2147483648L)));
        assertThat(jdbc.queryForObject("select rules_json from t_entity_risk_profile where tenant_id='counter-legacy'", String.class)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("select alert_count from t_entity_risk_profile where tenant_id='counter-legacy'", Long.class)).isEqualTo(11);
    }

    @Test void independentWritersRecheckDuplicateReceiptsAfterTheProfileLock() throws Exception {
        concurrentWrites(true);
    }

    @Test void independentWritersConvertLegacyHistoryOnlyOnce() throws Exception {
        concurrentWrites(false);
    }

    private void concurrentWrites(boolean duplicate) throws Exception {
        String tenant = "counter-concurrent";
        TenantContext.set(tenant);
        seedLegacy(tenant, "host-a", "{\"T1110\":1}", "{\"rule\":1}", 1);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        var repositoryProxy = new ProxyFactory(alerts);
        repositoryProxy.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findByTenantIdAndAlertId") && reads.incrementAndGet() <= 2)
                barrier.await(5, TimeUnit.SECONDS);
            return result;
        });
        var synchronizedReads = (EntityRiskAlertRepository) repositoryProxy.getProxy();
        EntityRiskStore first = transactionalStore(synchronizedReads);
        EntityRiskStore second = transactionalStore(synchronizedReads);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> TenantContext.runWith(tenant, () -> first.recordForAlert("concurrent-1", "host-a", Severity.HIGH, "T1110", "r", "rule", 0)));
            var two = executor.submit(() -> TenantContext.runWith(tenant, () -> second.recordForAlert(duplicate ? "concurrent-1" : "concurrent-2", "host-a", Severity.HIGH, "T1110", "r", "rule", 0)));
            one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
        }
        long expected = duplicate ? 2 : 3;
        assertThat(jdbc.queryForObject("select alert_count from t_entity_risk_profile where tenant_id=?", Long.class, tenant)).isEqualTo(expected);
        assertThat(jdbc.queryForList("select hit_count from t_entity_risk_counter where tenant_id=?", Long.class, tenant)).containsExactlyInAnyOrder(expected, expected);
        assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_alert where tenant_id=?", Long.class, tenant)).isEqualTo(expected - 1);
    }

    private EntityRiskStore transactionalStore(EntityRiskAlertRepository receiptRepository) {
        var proxy = new ProxyFactory(new EntityRiskStore(profiles, receiptRepository, counters));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (EntityRiskStore) proxy.getProxy();
    }

    void seedLegacy(String tenant, String entity, String mitre, String rules, long count) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into t_entity_risk_profile(entity_value,tenant_id,entity_key,score,score_at,alert_count,first_seen,last_seen,max_severity,mitre_json,rules_json,row_version)
                values (?,?,?,40,?,?,?,?,'HIGH',?,?,0)
                """, UUID.randomUUID().toString(), tenant, entity, now, count, now, now, mitre, rules);
    }
}
