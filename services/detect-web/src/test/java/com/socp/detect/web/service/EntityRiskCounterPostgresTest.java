package com.socp.detect.web.service;

import com.socp.platform.test.MiddlewareImages;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantRlsDataSource;
import com.socp.rule.model.Severity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import com.socp.detect.web.persistence.repository.EntityRiskProfileRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityRiskCounterPostgresTest extends EntityRiskCounterPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("risk_counters").withUsername("socp").withPassword("socp-test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test void detailKeepsProfileAndCountersInOneSnapshot() throws Exception {
        TenantContext.set("counter-snapshot");
        store.recordForAlert("before", "host", Severity.HIGH, null, "r", "rule", 0);
        try (var writer = Executors.newSingleThreadExecutor()) {
            var repositoryProxy = new ProxyFactory(profiles);
            repositoryProxy.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (invocation.getMethod().getName().equals("findByTenantIdAndEntity")) {
                    writer.submit(() -> TenantContext.runWith("counter-snapshot", () ->
                            store.recordForAlert("during", "host", Severity.HIGH, null, "r", "rule", 0)))
                            .get(10, TimeUnit.SECONDS);
                }
                return result;
            });
            var readerProxy = new ProxyFactory(new EntityRiskStore(
                    (EntityRiskProfileRepository) repositoryProxy.getProxy(), alerts, counters));
            readerProxy.setProxyTargetClass(true);
            readerProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            var detail = ((EntityRiskStore) readerProxy.getProxy()).get("host");
            assertThat(detail.get("alerts")).isEqualTo(1L);
            assertThat(detail.get("topRules")).isEqualTo(List.of(Map.of("rule", "rule", "count", 1L)));
        }
        assertThat(store.get("host").get("alerts")).isEqualTo(2L);
        assertThat(store.get("host").get("topRules")).isEqualTo(List.of(Map.of("rule", "rule", "count", 2L)));
    }

    @Test void upgradePreservesLegacyCountersUntilTheirFirstWrite() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("counter_upgrade").locations("classpath:db/migration").target("30").load().migrate();
        jdbc.update("""
                insert into counter_upgrade.t_entity_risk_profile
                (entity_value,tenant_id,entity_key,score,score_at,alert_count,first_seen,last_seen,max_severity,mitre_json,rules_json,row_version)
                values ('legacy-id','counter-upgrade','host',40,current_timestamp,10,current_timestamp,current_timestamp,'HIGH','{"T1110":2}','{"legacy":2147483647}',7)
                """);
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("counter_upgrade").locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("select counters_migrated from counter_upgrade.t_entity_risk_profile", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select rules_json from counter_upgrade.t_entity_risk_profile", String.class)).isEqualTo("{\"legacy\":2147483647}");
        assertThat(jdbc.queryForObject("select row_version from counter_upgrade.t_entity_risk_profile", Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("select count(*) from counter_upgrade.t_entity_risk_counter", Long.class)).isZero();
    }

    @Test void restrictedRoleCannotReadOrReferenceAnotherTenantsProfile() {
        TenantContext.runWith("counter-a", () -> store.recordForAlert("a", "host", Severity.HIGH, null, "r", "rule", 0));
        TenantContext.runWith("counter-b", () -> store.recordForAlert("b", "host", Severity.HIGH, null, "r", "rule", 0));
        String a = jdbc.queryForObject("select entity_value from t_entity_risk_profile where tenant_id='counter-a'", String.class);
        String b = jdbc.queryForObject("select entity_value from t_entity_risk_profile where tenant_id='counter-b'", String.class);
        jdbc.execute("create role risk_counter_app login password 'counter-test-only' nosuperuser nobypassrls");
        jdbc.execute("grant usage on schema public to risk_counter_app");
        jdbc.execute("grant select on t_entity_risk_profile to risk_counter_app");
        jdbc.execute("grant select,insert,update,delete on t_entity_risk_counter to risk_counter_app");
        for (String table : List.of("t_entity_risk_profile", "t_entity_risk_counter")) {
            jdbc.execute("alter table " + table + " enable row level security");
            jdbc.execute("alter table " + table + " force row level security");
            jdbc.execute("create policy socp_tenant_isolation on " + table
                    + " using (tenant_id=current_setting('socp.tenant_id',true)) with check (tenant_id=current_setting('socp.tenant_id',true))");
        }
        var source = new TenantRlsDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "risk_counter_app", "counter-test-only"));
        var scopedJdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(new EntityRiskCounterStore(scopedJdbc));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var scoped = (EntityRiskCounterStore) proxy.getProxy();
        TenantContext.set("counter-a");
        assertThat(scoped.readTop("counter-a", List.of(a, b))).containsOnlyKeys(a);
        assertThat(scopedJdbc.queryForObject("select count(*) from t_entity_risk_counter", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> scoped.readTop("counter-b", List.of(b))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(ignored -> scoped.increment("counter-a", b, "RULE", "cross")))
                .isInstanceOf(DataIntegrityViolationException.class);
        TenantContext.clear();
        assertThat(scopedJdbc.queryForObject("select count(*) from t_entity_risk_counter", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from t_entity_risk_counter", Long.class)).isEqualTo(2);
    }
}
