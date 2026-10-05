package com.socp.incident.web.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.incident.web.api.request.CaseAlarmAssociationRequest;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.incident.web.service.CaseService;
import com.socp.incident.web.service.CaseAlarmAssociationService;
import com.socp.incident.web.service.IncidentAggregationLock;
import com.socp.platform.auth.security.OperatorDirectory;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({CaseStore.class, CaseService.class, IncidentAggregationLock.class,
        CaseAlarmAssociationService.class, CaseAlarmAssociationPersistenceTest.Config.class})
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseAlarmAssociationPersistenceTest {
    @org.springframework.test.context.DynamicPropertySource
    static void nativePostgres(org.springframework.test.context.DynamicPropertyRegistry properties) {
        String url = System.getenv("SOCP_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/socp_downstream_[a-z]+"))
            throw new IllegalArgumentException("Use a dedicated loopback socp_downstream_* test database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("SOCP_TEST_JDBC_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("SOCP_TEST_JDBC_PASSWORD", ""));
        properties.add("spring.test.database.replace", () -> "NONE");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @Autowired CaseStore store;
    @Autowired CaseService automatic;
    @Autowired CaseAlarmAssociationService commands;
    @Autowired JdbcTemplate jdbc;
    @MockBean AlertClient alerts;
    @MockBean OperatorDirectory operators;
    @TestConfiguration static class Config { @Bean ObjectMapper json() { return new ObjectMapper(); } }
    @BeforeEach void setup() {
        TenantContext.set("associate-" + java.util.UUID.randomUUID());
        jdbc.execute("create table if not exists t_incident_merge_lock (tenant_id varchar(64), shard_id integer, created_at timestamp, primary key(tenant_id,shard_id))");
        jdbc.execute("create table if not exists t_incident_alarm_exclusion (tenant_id varchar(64), alarm_id varchar(255), created_at timestamp, primary key(tenant_id,alarm_id))");
        when(alerts.getAlarm("alarm-1")).thenReturn(new ServiceCall(SocpService.ALERT, "http://alert", true,
                200, "{\"code\":0,\"data\":{\"id\":\"alarm-1\",\"tenantId\":\"" + TenantContext.require() + "\",\"ruleId\":\"r-1\"}}", null, 1, false, 1));
    }
    @AfterEach void clear() { TenantContext.clear(); }
    Case create() { return store.save(Case.create("Case", "host", "HIGH")); }
    CaseAlarmAssociationRequest command(Case from, String op, Case to, String key) {
        return new CaseAlarmAssociationRequest("alarm-1", op, to == null ? null : to.id(),
                from.rowVersion(), to == null ? null : to.rowVersion(), key, "Analyst correlated evidence");
    }
    @Test void attachMoveDetachAndAutomaticRedeliveryRespectAnalystDecision() {
        Case first = create(), second = create();
        var attach = command(first, "ATTACH", null, "attach");
        commands.change(first.id(), "alice", attach);
        assertThat(store.alarms(first.id(), 0, 10).getContent()).containsExactly("alarm-1");
        assertThat(commands.change(first.id(), "alice", attach)).containsEntry("duplicate", true);
        first = store.getMetadata(first.id());
        commands.change(first.id(), "alice", command(first, "MOVE", second, "move"));
        assertThat(store.alarms(first.id(), 0, 10).getContent()).isEmpty();
        assertThat(store.alarms(second.id(), 0, 10).getContent()).containsExactly("alarm-1");
        assertThat(automatic.fromAlarm(Map.of("id", "alarm-1", "entity", "other-host")))
                .containsEntry("caseId", second.id()).containsEntry("duplicate", true);
        second = store.getMetadata(second.id());
        commands.change(second.id(), "alice", command(second, "DETACH", null, "detach"));
        assertThat(automatic.fromAlarm(Map.of("id", "alarm-1", "entity", "host")))
                .containsEntry("detached", true);
        assertThat(store.alarms(second.id(), 0, 10).getContent()).isEmpty();
        assertThat(store.timeline(second.id(), 0, 10).getTotalElements()).isEqualTo(2);
        second = store.getMetadata(second.id());
        commands.change(second.id(), "alice", command(second, "ATTACH", null, "reattach"));
        assertThat(store.alarms(second.id(), 0, 10).getContent()).containsExactly("alarm-1");
    }
    @Test void analystKeyCannotCollideWithHistoryOfAnIncomingMove() {
        Case first = create(), second = create();
        commands.change(first.id(), "alice", command(first, "ATTACH", null, "attach"));
        first = store.getMetadata(first.id());
        commands.change(first.id(), "alice", command(first, "MOVE", second, "k"));
        Case beforeDetach = store.getMetadata(second.id());
        var detach = command(beforeDetach, "DETACH", null, first.id() + ":k");
        commands.change(second.id(), "alice", detach);
        assertThat(store.getMetadata(second.id()).rowVersion()).isGreaterThan(beforeDetach.rowVersion());
        assertThat(store.timeline(second.id(), 0, 10).getTotalElements()).isEqualTo(2);
        assertThat(store.alarms(second.id(), 0, 10).getContent()).isEmpty();
        assertThat(commands.change(second.id(), "alice", detach)).containsEntry("duplicate", true);
        assertThat(store.timeline(second.id(), 0, 10).getTotalElements()).isEqualTo(2);
    }

    @Test void concurrentMovesHaveOneWinnerAndNeverAssociateTheAlarmTwice() throws Exception {
        Case source = create(), targetA = create(), targetB = create();
        commands.change(source.id(), "alice", command(source, "ATTACH", null, "attach"));
        Case current = store.getMetadata(source.id());
        String tenant = TenantContext.require();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var outcomes = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (Case target : java.util.List.of(targetA, targetB)) outcomes.add(pool.submit(() -> {
                start.await();
                return TenantContext.callWith(tenant, () -> {
                    try {
                        commands.change(current.id(), "alice", command(current, "MOVE", target, "move-" + target.id()));
                        return true;
                    } catch (com.socp.platform.error.exception.ApiException conflict) {
                        assertThat(conflict.getCode()).isEqualTo(409);
                        return false;
                    }
                });
            }));
            start.countDown();
            int winners = 0;
            for (var outcome : outcomes) if (outcome.get(15, java.util.concurrent.TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.alarms(source.id(), 0, 10).getContent()).isEmpty();
        assertThat(store.alarms(targetA.id(), 0, 10).getTotalElements()
                + store.alarms(targetB.id(), 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(store.timeline(source.id(), 0, 10).getTotalElements()).isEqualTo(2);
    }

    @Test void staleVersionAndForeignAlarmCannotChangeAssociations() {
        Case first = create();
        commands.change(first.id(), "alice", command(first, "ATTACH", null, "first"));
        assertThatThrownBy(() -> commands.change(first.id(), "bob", command(first, "DETACH", null, "stale")))
                .hasMessageContaining("refresh");
        Case second = create();
        when(alerts.getAlarm("alarm-1")).thenReturn(new ServiceCall(SocpService.ALERT, "http://alert", true,
                200, "{\"id\":\"alarm-1\",\"tenantId\":\"foreign\"}", null, 1, false, 1));
        assertThatThrownBy(() -> commands.change(second.id(), "alice", command(second, "ATTACH", null, "foreign")))
                .hasMessageContaining("Alarm not found");
        assertThat(store.alarms(second.id(), 0, 10).getContent()).isEmpty();
    }
}
