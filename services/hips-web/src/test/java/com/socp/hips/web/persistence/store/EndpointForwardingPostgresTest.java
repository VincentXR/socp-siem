package com.socp.hips.web.persistence.store;

import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class EndpointForwardingPostgresTest extends EndpointForwardingPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("endpoint_forwarding").withUsername("socp").withPassword("socp");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @org.junit.jupiter.api.Test
    void restrictedRlsRoleCanEnforceGlobalAdmissionAndDrainAllTenants() {
        jdbc.execute("create role hips_forwarding_app login password 'fixture-password' nosuperuser nobypassrls");
        jdbc.execute("alter table t_endpoint_forwarding enable row level security");
        jdbc.execute("alter table t_endpoint_forwarding force row level security");
        jdbc.execute("""
                create policy socp_tenant_isolation on t_endpoint_forwarding
                using (current_setting('socp.tenant_id',true)='*' or tenant_id=current_setting('socp.tenant_id',true))
                with check (current_setting('socp.tenant_id',true)='*' or tenant_id=current_setting('socp.tenant_id',true))
                """);
        jdbc.execute("grant select,insert,update,delete on t_endpoint_forwarding,t_endpoint_forwarding_admission to hips_forwarding_app");
        var raw = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "hips_forwarding_app", "fixture-password");
        var scopedSource = new com.socp.platform.tenant.persistence.TenantRlsDataSource(raw);
        var scopedJdbc = new org.springframework.jdbc.core.JdbcTemplate(scopedSource);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(scopedSource);
        var proxy = new org.springframework.aop.framework.ProxyFactory(new EndpointForwardingStore(scopedJdbc, 3, 2, 2));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var scopedStore = (EndpointForwardingStore) proxy.getProxy();
        jdbc.execute("alter table t_endpoint_event enable row level security");
        jdbc.execute("alter table t_endpoint_event force row level security");
        jdbc.execute("""
                create policy socp_tenant_isolation on t_endpoint_event
                using (current_setting('socp.tenant_id',true)='*' or tenant_id=current_setting('socp.tenant_id',true))
                with check (current_setting('socp.tenant_id',true)='*' or tenant_id=current_setting('socp.tenant_id',true))
                """);
        jdbc.execute("grant select,insert,update,delete on t_endpoint_event to hips_forwarding_app");
        var retentionProxy = new org.springframework.aop.framework.ProxyFactory(new EndpointHistoryRetentionStore(scopedJdbc));
        retentionProxy.setProxyTargetClass(true);
        retentionProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var scopedRetention = (EndpointHistoryRetentionStore) retentionProxy.getProxy();
        var requestIdentity = new EndpointForwardingStore.RequestIdentity("producer", "key", "fingerprint");
        com.socp.platform.tenant.context.TenantContext.runWith("tenant-a", () -> {
            scopedStore.enqueue("a1", "tenant-a", "{}", java.time.Instant.EPOCH, requestIdentity);
            scopedStore.enqueue("a2", "tenant-a", "{}", java.time.Instant.EPOCH);
        });
        com.socp.platform.tenant.context.TenantContext.runWith("tenant-b", () ->
                scopedStore.enqueue("b1", "tenant-b", "{}", java.time.Instant.EPOCH, requestIdentity));
        com.socp.platform.tenant.context.TenantContext.runWith("tenant-a", () -> {
            org.assertj.core.api.Assertions.assertThat(scopedStore.findRequest("tenant-a", requestIdentity).orElseThrow().eventId()).isEqualTo("a1");
            org.assertj.core.api.Assertions.assertThat(scopedStore.findRequest("tenant-b", requestIdentity)).isEmpty();
        });
        com.socp.platform.tenant.context.TenantContext.runWith("tenant-b", () ->
                org.assertj.core.api.Assertions.assertThat(scopedStore.findRequest("tenant-b", requestIdentity).orElseThrow().eventId()).isEqualTo("b1"));
        for (String id : java.util.List.of("a1", "b1", "old-a", "old-b")) {
            jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values(?,?,?,'{}')",
                    id, id.contains("b") ? "tenant-b" : "tenant-a", java.sql.Timestamp.from(java.time.Instant.EPOCH));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scopedRetention.prune(java.time.Instant.now(), 100))
                .isInstanceOf(IllegalStateException.class);
        try (var ignored = com.socp.platform.tenant.context.TenantContext.openSystem()) {
            org.assertj.core.api.Assertions.assertThat(scopedRetention.prune(java.time.Instant.now(), 100)).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(scopedRetention.oldestEligible(java.time.Instant.now())).isEmpty();
        }
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList("select event_id from t_endpoint_event", String.class))
                .containsExactlyInAnyOrder("a1", "b1");
        org.assertj.core.api.Assertions.assertThat(scopedJdbc.queryForObject("select count(*) from t_endpoint_event", Long.class)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                com.socp.platform.tenant.context.TenantContext.runWith("tenant-c", () ->
                        scopedStore.enqueue("c1", "tenant-c", "{}", java.time.Instant.EPOCH)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThat(scopedJdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isZero();
        com.socp.platform.tenant.context.TenantContext.runWith("tenant-a", () -> {
            org.assertj.core.api.Assertions.assertThat(scopedJdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(scopedStore.status("tenant-b", "b1")).isEqualTo("UNKNOWN");
        });
        var http = org.mockito.Mockito.mock(com.socp.platform.client.http.SocpHttpClient.class);
        org.mockito.Mockito.when(http.post(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call -> {
            org.assertj.core.api.Assertions.assertThat(com.socp.platform.tenant.context.TenantContext.isSystemScope()).isFalse();
            org.assertj.core.api.Assertions.assertThat(com.socp.platform.tenant.context.TenantContext.require()).isIn("tenant-a", "tenant-b");
            return new com.socp.platform.client.http.ServiceCall(com.socp.platform.client.service.SocpService.SEARCH,
                    "http://fixture", true, 200, "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}}",
                    null, 1, false, 1);
        });
        new com.socp.hips.web.service.EndpointForwardingPublisher(scopedStore, http, new com.fasterxml.jackson.databind.ObjectMapper(), true).drain();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from t_endpoint_forwarding where status='DELIVERED'", Long.class)).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(com.socp.platform.tenant.context.TenantContext.isSystemScope()).isFalse();
        org.assertj.core.api.Assertions.assertThat(scopedJdbc.queryForObject("select count(*) from t_endpoint_forwarding", Long.class)).isZero();
    }
}
