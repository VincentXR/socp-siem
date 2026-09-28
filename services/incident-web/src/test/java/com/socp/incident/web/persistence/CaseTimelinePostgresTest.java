package com.socp.incident.web.persistence;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.incident.web.persistence.repository.CaseTimelineRepository;
import com.socp.incident.web.persistence.repository.AlarmCaseLinkRepository;
import com.socp.incident.web.api.controller.CaseController;
import com.socp.incident.web.service.CaseService;
import com.socp.incident.web.service.IncidentAggregationLock;
import com.socp.platform.audit.aspect.AuditAspect;
import com.socp.platform.audit.sink.JdbcAuditOutboxSink;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.audit.model.AuditRecord;
import jakarta.persistence.EntityManagerFactory;
import com.socp.platform.test.MiddlewareImages;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real app repository proof for normalized timeline size and idempotency. */
@DataJpaTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CaseStore.class, CaseTimelinePostgresTest.ProxyConfiguration.class})
class CaseTimelinePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("incident")
            .withUsername("socp")
            .withPassword("socp");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    @Autowired
    private CaseStore store;

    @Autowired private CaseTimelineRepository timelineRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CaseController controller;
    @Autowired private CaseService caseService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditSink auditSink;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void timelinePreviewIsDatabaseBoundedAndPagesHaveStableTimestampTies() {
        TenantContext.set("bounded-timeline-tenant");
        CaseTimelineReadAssertions.verify(store, timelineRepository, entityManagerFactory);
    }

    @BeforeEach
    void setTenant() {
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void normalizedTimelineUsesUniqueEventKeyAndSupportsPaging() {
        Case incident = Case.create("timeline", "host-1", "HIGH");
        store.save(incident);
        TimelineEvent event = new TimelineEvent(Instant.now(), "NOTE", "same note", "test", null, "note-1");

        assertThat(store.appendTimeline(incident.id(), event)).isTrue();
        assertThat(store.appendTimeline(incident.id(), event)).isFalse();
        assertThat(store.timeline(incident.id(), 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(store.get(incident.id()).timeline()).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditedNoteAndSuccessIntentRollbackTogetherWhenAuditInsertFails() {
        Case incident = caseService.create("atomic audit", "host-a", "HIGH", "analyst");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_note_audit() RETURNS trigger AS $$
                BEGIN
                  IF NEW.action = 'ADD_INCIDENT_NOTE' THEN
                    RAISE EXCEPTION 'audit sink unavailable';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_note_audit BEFORE INSERT ON t_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION reject_note_audit()
                """);
        try {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                    controller.note(incident.id(), "analyst", "must rollback", "note-atomic")))
                    .isNotNull();
            assertThat(store.timeline(incident.id(), 0, 10).getTotalElements()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_note_audit ON t_audit_outbox");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_note_audit()");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateAuditIdentityDoesNotAbortTheBusinessTransaction() {
        jdbc.execute("CREATE TABLE audit_atomic_business (id VARCHAR(64) PRIMARY KEY)");
        try {
            AuditRecord record = new AuditRecord("fixed-audit-event", "tenant-a", "TEST",
                    "analyst", "case", "SUCCESS", Instant.now());
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.update("INSERT INTO audit_atomic_business(id) VALUES ('business-1')");
                        auditSink.publish(record);
                        auditSink.publish(record);
                    });

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM audit_atomic_business", Long.class)).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM t_audit_outbox WHERE event_id='fixed-audit-event'",
                    Long.class)).isOne();
        } finally {
            jdbc.execute("DROP TABLE audit_atomic_business");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedBusinessMutationGetsSeparateFailureAuditAndDuplicateNoteIsIdempotent() {
        long before = countAudit("ADD_INCIDENT_NOTE");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                controller.note("missing", "analyst", "no case", "missing-note"))).isNotNull();
        assertThat(countAudit("ADD_INCIDENT_NOTE")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("""
                SELECT result_text FROM t_audit_outbox
                WHERE tenant_id=? AND action='ADD_INCIDENT_NOTE'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, "tenant-a")).startsWith("FAIL:");

        Case incident = caseService.create("idempotent", "host-b", "MEDIUM", null);
        controller.note(incident.id(), "analyst", "once", "same-key");
        controller.note(incident.id(), "analyst", "once", "same-key");
        assertThat(store.timeline(incident.id(), 0, 10).getTotalElements()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAggregationCompletesWithTwoConnectionsAndCreatesOneCase() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> aggregate(start, "tenant-concurrent", "AL-CONCURRENT-1", "shared-host"));
            var second = pool.submit(() -> aggregate(start, "tenant-concurrent", "AL-CONCURRENT-2", "shared-host"));
            String firstCase = first.get();
            String secondCase = second.get();
            assertThat(firstCase).isEqualTo(secondCase);
        }
        TenantContext.set("tenant-concurrent");
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.get(store.list().getFirst().id()).alarmIds()).hasSize(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameAlarmCannotRaceIntoDifferentEntityCases() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> aggregate(start, "tenant-duplicate", "AL-SAME", "host-a"));
            var second = pool.submit(() -> aggregate(start, "tenant-duplicate", "AL-SAME", "host-b"));
            assertThat(first.get()).isEqualTo(second.get());
        }
        TenantContext.set("tenant-duplicate");
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.get(store.list().getFirst().id()).alarmIds()).containsExactly("AL-SAME");
    }

    private String aggregate(CyclicBarrier start, String tenant, String alarmId, String entity) throws Exception {
        TenantContext.set(tenant);
        try {
            start.await();
            return String.valueOf(caseService.fromAlarm(Map.of(
                    "id", alarmId, "ruleId", "RULE-1", "ruleName", "Rule",
                    "severity", "HIGH", "entity", entity, "message", "hit",
                    "occurredAt", "2026-09-28T00:00:00Z")).get("caseId"));
        } finally {
            TenantContext.clear();
        }
    }

    private long countAudit(String action) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM t_audit_outbox WHERE tenant_id=? AND action=?",
                Long.class, "tenant-a", action);
        return count == null ? 0 : count;
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class ProxyConfiguration {
        @Bean AuditSink auditSink(JdbcTemplate jdbc) { return new JdbcAuditOutboxSink(jdbc); }
        @Bean AuditAspect auditAspect(AuditSink sink,
                                     ObjectProvider<PlatformTransactionManager> managers) {
            return new AuditAspect(sink, managers);
        }
        @Bean IncidentAggregationLock aggregationLock(JdbcTemplate jdbc) {
            return new IncidentAggregationLock(jdbc);
        }
        @Bean CaseService caseService(CaseStore store, AlarmCaseLinkRepository alarms,
                                     IncidentAggregationLock lock) {
            return new CaseService(store, alarms, lock);
        }
        @Bean CaseController caseController(CaseService service) {
            return new CaseController(service, 500,
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        }
    }
}
