package com.socp.detect.web.persistence.store;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(showSql=false, properties="spring.jpa.properties.hibernate.generate_statistics=true")
@Import({DetectionEventJournal.class, DetectionAlertOutboxService.class, DetectionBatchPersistenceTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class DetectionBatchPersistenceTest {
    @TestConfiguration static class Config {
        @Bean AlertForwarder forwarder(DetectionStateStore state, DetectionAlertOutboxService outbox) {
            var rules=mock(RuleSpecStore.class);
            var risk=mock(com.socp.detect.web.service.EntityRiskStore.class);
            when(risk.recordForAlert(anyString(),anyString(),any(),any(),anyString(),anyString(),anyInt()))
                    .thenReturn(new com.socp.rule.score.RiskScorer.Score(45,"MEDIUM",Map.of()));
            return new AlertForwarder(rules,risk,outbox,state);
        }
    }
    @Autowired DetectionEventJournal journal;
    @Autowired AlertForwarder forwarder;
    @Autowired DetectionEventRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory factory;
    private String tenant;
    @BeforeEach void open() { tenant="batch-"+UUID.randomUUID(); TenantContext.set(tenant); }
    @AfterEach void close() { jdbc.update("delete from t_detection_alert_outbox where tenant_id = ?",tenant); jdbc.update("delete from t_detection_event where tenant_id = ?",tenant);TenantContext.clear(); }
    private DetectionEventInput input(String id,int offset) {
        return new DetectionEventInput(new SecurityEvent(id, Instant.now(),"auth","host","raw",
                Map.of("tenant_id",tenant),Severity.INFO),"events",0,offset,"route");
    }
    private List<DetectionEventInput> inputs() {
        return java.util.stream.IntStream.range(0,32).mapToObj(n->input("event-"+n,n)).toList();
    }
    private List<DetectionResult> results(List<DetectionEventInput> inputs) {
        return inputs.stream().map(i->new DetectionResult(i.event(),new DetectionResult.InputPosition(i.topic(),i.partition(),i.offset()),
                Map.of("rule","v1"),List.of(),List.of(),List.of(),null,i.event().scopedId())).toList();
    }

    @Test void pendingStatisticsRemainExactAcrossCompletionTenantsAndTopics() {
        var accepted = inputs();
        journal.claimBatch(accepted);
        assertEquals(32, repository.countByTenantIdAndPendingTopic(tenant, "events"));
        assertEquals(0, repository.countByTenantIdAndPendingTopic(tenant, "other-topic"));
        assertEquals(0, repository.countByTenantIdAndPendingTopic(tenant + "-other", "events"));
        assertEquals(32, repository.countPendingByTopic("events"));
        assertThrows(IllegalStateException.class, () -> forwarder.forwardBatch(results(accepted), () -> {
            throw new IllegalStateException("ownership lost");
        }));
        assertEquals(32, repository.countByTenantIdAndPendingTopic(tenant, "events"));
        forwarder.forwardBatch(results(accepted.subList(0, 16)), () -> { });
        assertEquals(16, repository.countByTenantIdAndPendingTopic(tenant, "events"));
        forwarder.forwardBatch(results(accepted.subList(16, 32)), () -> { });
        assertEquals(0, repository.countByTenantIdAndPendingTopic(tenant, "events"));
        assertEquals(32, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
    }
    @Test void legacyPhysicalIdentitySurvivesReplayAndCompletionWithTimeLocalKeys() {
        var input = List.of(input("legacy-key", 0));
        assertEquals(List.of(DetectionEventClaim.NEW), journal.claimBatch(input));
        var row = repository.findByTenantIdAndDeliveryId(tenant, "legacy-key").orElseThrow();
        assertEquals(7, UUID.fromString(row.getStorageId()).version());
        String legacy = UUID.nameUUIDFromBytes((tenant + "|legacy-key")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        jdbc.update("update t_detection_event set event_id=? where tenant_id=? and delivery_id=?", legacy, tenant, "legacy-key");
        assertEquals(List.of(DetectionEventClaim.PENDING), journal.claimBatch(input));
        forwarder.forwardBatch(results(input), () -> { });
        assertEquals(List.of(DetectionEventClaim.COMPLETED), journal.claimBatch(input));
        assertEquals(legacy, repository.findByTenantIdAndDeliveryId(tenant, "legacy-key").orElseThrow().getStorageId());
        assertEquals(1, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
    }

    @Test void distinctPhysicalKeysCannotDuplicateOneTenantDeliveryUnderConcurrentInsert() throws Exception {
        var first = new com.socp.detect.web.persistence.entity.DetectionEventEntity(tenant, "racing-key", "auth", "host",
                "raw", "{}", "INFO", Instant.now(), 0, 0L, "route");
        var second = new com.socp.detect.web.persistence.entity.DetectionEventEntity(tenant, "racing-key", "auth", "host",
                "raw", "{}", "INFO", Instant.now(), 0, 0L, "route");
        assertNotEquals(first.getStorageId(), second.getStorageId());
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(row -> workers.submit(() -> {
                try (var ignored = TenantContext.open(tenant)) {
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try { repository.saveAndFlush(row); return true; }
                    catch (org.springframework.dao.DataIntegrityViolationException duplicate) { return false; }
                }
            })).toList();
            int inserted = 0;
            for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) inserted++;
            assertEquals(1, inserted);
            assertEquals(1, repository.countByTenantIdAndStatus(tenant, "PENDING"));
        }
    }

    @Test void claimsAndCompletionAreBatchedAndTerminalReceiptIsImmutable() {
        var input=inputs();var stats=factory.unwrap(SessionFactory.class).getStatistics();stats.clear();
        assertTrue(journal.claimBatch(input).stream().allMatch(c->c==DetectionEventClaim.NEW));
        assertTrue(stats.getPrepareStatementCount()<=4,"claim prepared "+stats.getPrepareStatementCount());
        journal.markDeadLettered("event-31", "terminal dead-letter evidence");
        stats.clear();forwarder.forwardBatch(results(input),()->{});
        assertTrue(stats.getPrepareStatementCount()<=4,"completion prepared "+stats.getPrepareStatementCount());
        assertEquals(input.subList(0,31).stream().map(i->i.event().id()).toList(),
                repository.findByTenantIdAndStatusAndCompletedAtAfterOrderByCompletedAt(tenant,"COMPLETED",
                        Instant.now().minusSeconds(60),org.springframework.data.domain.PageRequest.of(0,100))
                        .stream().map(row->row.getDeliveryId()).toList(), "recovery must retain evaluation order");
        var first=repository.findByTenantIdAndDeliveryId(tenant,"event-0").orElseThrow();
        assertEquals("COMPLETED",first.getStatus());assertNotEquals("{}",first.getResultJson());
        forwarder.forwardBatch(results(input),()->{});
        assertEquals(first.getCompletedAt(),repository.findByTenantIdAndDeliveryId(tenant,"event-0").orElseThrow().getCompletedAt());
        var claims = journal.claimBatch(input);
        assertTrue(claims.subList(0,31).stream().allMatch(c->c==DetectionEventClaim.COMPLETED));
        assertEquals(DetectionEventClaim.DEAD_LETTERED, claims.getLast());
        var dead = repository.findByTenantIdAndDeliveryId(tenant,"event-31").orElseThrow();
        assertEquals("terminal dead-letter evidence", dead.getStatusReason());
        assertEquals("{}", dead.getResultJson());
        assertNull(dead.getCompletedAt());
    }

    @Test void queuedEngineBatchesKeepEveryOwnershipFenceInsideTheJournalTransaction() throws Exception {
        var input = java.util.stream.IntStream.range(0, 256)
                .mapToObj(n -> input("event-" + n, n)).toList();
        journal.claimBatch(input.subList(0, 128));
        journal.claimBatch(input.subList(128, 256));
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new AtomicInteger();
        var positioned = new AtomicInteger();
        var sink = new com.socp.rule.engine.BatchEventAlertSink() {
            public boolean supportsAtomicBatch() { return true; }
            public void publishBatch(List<Entry> entries) { calls.incrementAndGet(); forwarder.forwardBatch(entries); }
            public void publish(SecurityEvent event, List<Alert> alerts) { fail("unexpected single-event sink"); }
            public void close() { }
        };
        com.socp.rule.engine.RuleExecutionScope scope = event -> {
            var context = TenantContext.open(event.requireTenantId());
            return context::close;
        };
        var events = input.stream().map(i -> new com.socp.rule.engine.RuleEngine.BatchEvent(i.event(),
                new DetectionResult.InputPosition(i.topic(), i.partition(), i.offset()), scope)).toList();
        var firstGuard = new com.socp.rule.engine.OrderedCommitGuard("a", () -> {
            assertEquals(tenant, TenantContext.require());
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        });
        var secondGuard = new com.socp.rule.engine.OrderedCommitGuard("b", () -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if (fail.get()) throw new IllegalStateException("second state unit lost ownership");
        });
        try (var engine = new com.socp.rule.engine.RuleEngine(List.of(), List.of(sink))) {
            var first = engine.ingestBatchAndAwait(events.subList(0, 128), positioned::incrementAndGet, firstGuard);
            var second = engine.ingestBatchAndAwait(events.subList(128, 256), positioned::incrementAndGet, secondGuard);
            engine.start();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> first.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, calls.get(), "queued callers must reach one result transaction");
            assertEquals(0, positioned.get());
            assertEquals(256, repository.countByTenantIdAndStatus(tenant, "PENDING"));
            assertEquals(0, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
            fail.set(false);
            forwarder.forwardBatch(results(input).stream().map(result ->
                    new com.socp.rule.engine.BatchEventAlertSink.Entry(result,
                            result.event().id().equals("event-0") ? secondGuard : null, scope)).toList());
            assertEquals(256, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
            for (int start = 0; start < 256; start += 128) {
                engine.ingestBatchAndAwait(events.subList(start, start + 128), positioned::incrementAndGet, secondGuard)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(256, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
            var oversized = new ArrayList<>(results(input));
            oversized.add(results(input).getFirst());
            assertThrows(IllegalArgumentException.class, () -> journal.markCompletedBatch(oversized));
            assertThrows(IllegalArgumentException.class, () -> forwarder.forwardBatch(oversized.stream()
                    .map(result -> new com.socp.rule.engine.BatchEventAlertSink.Entry(result, null, scope)).toList()));
        }
    }
    @Test void finalFenceFailureRollsBackEveryCompletionAndRetryCommitsThemTogether() {
        var input=inputs();journal.claimBatch(input);AtomicInteger checks=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->forwarder.forwardBatch(results(input),()->{
            if(checks.incrementAndGet()==2)throw new IllegalStateException("owner fenced before commit");
        }));
        assertEquals(32,repository.countByTenantIdAndStatus(tenant,"PENDING"));
        assertEquals(0,repository.countByTenantIdAndStatus(tenant,"COMPLETED"));
        forwarder.forwardBatch(results(input),()->{});
        assertEquals(32,repository.countByTenantIdAndStatus(tenant,"COMPLETED"));
    }
    @Test void oneInvalidInsertionRollsBackTheEntireJournalBatch() {
        int size=com.socp.rule.engine.RuleEngine.MAX_BATCH_EVENTS;
        var input=new ArrayList<>(java.util.stream.IntStream.range(0,size)
                .mapToObj(n->input("event-"+n,n)).toList());
        // The final invalid row is in the second JDBC batch (configured size 64).
        // The earlier driver batch must still roll back with the transaction.
        input.set(size-1,input("x".repeat(200),size-1));
        assertThrows(RuntimeException.class,()->journal.claimBatch(input));
        assertEquals(0,repository.countByTenantIdAndStatus(tenant,"PENDING"));
    }

    @Test void maximumBatchKeepsBothJdbcChunksAtomicAndRejectsOverflow() {
        int size=com.socp.rule.engine.RuleEngine.MAX_BATCH_EVENTS;
        var oversized=java.util.stream.IntStream.range(0,size+1)
                .mapToObj(n->input("event-"+n,n)).toList();
        assertThrows(IllegalArgumentException.class,()->journal.claimBatch(oversized));
        assertEquals(0,repository.countByTenantIdAndStatus(tenant,"PENDING"));
        var input=oversized.subList(0,size);
        journal.claimBatch(input);
        assertThrows(IllegalArgumentException.class,()->forwarder.forwardBatch(results(oversized),()->{}));
        AtomicInteger checks=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->forwarder.forwardBatch(results(input),()->{
            if(checks.incrementAndGet()==2)throw new IllegalStateException("owner fenced after JDBC flush");
        }));
        assertEquals(size,repository.countByTenantIdAndStatus(tenant,"PENDING"));
        assertEquals(0,repository.countByTenantIdAndStatus(tenant,"COMPLETED"));
        forwarder.forwardBatch(results(input),()->{});
        assertEquals(size,repository.countByTenantIdAndStatus(tenant,"COMPLETED"));
        assertEquals(input.stream().map(i->i.event().id()).toList(),
                repository.findByTenantIdAndStatusAndCompletedAtAfterOrderByCompletedAt(tenant,"COMPLETED",
                        Instant.now().minusSeconds(60),org.springframework.data.domain.PageRequest.of(0,size))
                        .stream().map(row->row.getDeliveryId()).toList());
    }
    @Test void alertOutboxAndReceiptsShareRollbackAndKeepEachEventsTrace() {
        var input=inputs().subList(0,2);journal.claimBatch(input);
        var calculated=input.stream().map(i->{
            var alert=new Alert("rule","rule",Severity.HIGH,"hit","host",List.of(i.event()));
            return new DetectionResult(i.event(),new DetectionResult.InputPosition(i.topic(),i.partition(),i.offset()),
                    Map.of("rule","v1"),List.of(),List.of(alert),List.of(alert),null,i.event().scopedId());
        }).toList();
        List<com.socp.rule.engine.RuleExecutionScope> scopes=java.util.stream.IntStream.range(0,2).mapToObj(n->{
            String trace=(n==0?"a":"b").repeat(32);
            return (com.socp.rule.engine.RuleExecutionScope)event->{
                String previous=org.slf4j.MDC.get("traceId");
                org.slf4j.MDC.put("traceId",trace);
                return ()->{
                    if(previous==null) org.slf4j.MDC.remove("traceId");
                    else org.slf4j.MDC.put("traceId",previous);
                };
            };
        }).toList();
        AtomicInteger fences=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->forwarder.forwardBatch(calculated,scopes,()->{
            if(fences.incrementAndGet()==2)throw new IllegalStateException("fenced");
        }));
        assertEquals(0,jdbc.queryForObject("select count(*) from t_detection_alert_outbox where tenant_id = ?",Integer.class,tenant));
        assertEquals(2,repository.countByTenantIdAndStatus(tenant,"PENDING"));
        forwarder.forwardBatch(calculated,scopes,()->{});
        assertEquals(2,repository.countByTenantIdAndStatus(tenant,"COMPLETED"));
        for(int n=0;n<2;n++) {
            String trace=jdbc.queryForObject("select traceparent from t_detection_alert_outbox where tenant_id = ? and alert_id = ?",
                    String.class,tenant,calculated.get(n).alerts().getFirst().id());
            assertTrue(trace.startsWith("00-"+(n==0?"a":"b").repeat(32)+"-"));
        }
    }

    @Test void missingClaimOrMixedTenantNeverProducesPartialCompletion() {
        var input=inputs();journal.claimBatch(input.subList(0,31));
        assertThrows(IllegalStateException.class,()->forwarder.forwardBatch(results(input),()->{}));
        assertEquals(31,repository.countByTenantIdAndStatus(tenant,"PENDING"));
        var mixed=new ArrayList<>(input);var other=new SecurityEvent("foreign",Instant.now(),"auth","host","raw",Map.of("tenant_id","another-tenant"),Severity.INFO);
        mixed.set(31,new DetectionEventInput(other,"events",0,31,"route"));
        assertThrows(IllegalArgumentException.class,()->journal.claimBatch(mixed));
        assertEquals(31,repository.countByTenantIdAndStatus(tenant,"PENDING"));
    }

    @Test void independentStateBatchesCommitWhileASiblingTransactionIsBlockedAndRollsBack() throws Exception {
        var input = inputs();
        journal.claimBatch(input);
        var slow = results(input.subList(0, 16));
        var fast = results(input.subList(16, 32));
        var flushed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger fences = new AtomicInteger();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocked = workers.submit(() -> {
                try (var ignored = TenantContext.open(tenant)) {
                    forwarder.forwardBatch(slow, () -> {
                        if (fences.incrementAndGet() != 2) return;
                        flushed.countDown();
                        try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        throw new IllegalStateException("slow state unit lost ownership after flush");
                    });
                }
            });
            try {
                assertTrue(flushed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                workers.submit(() -> {
                    try (var ignored = TenantContext.open(tenant)) { forwarder.forwardBatch(fast, () -> { }); }
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(blocked.isDone());
                assertEquals(16, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
                release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> blocked.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(16, repository.countByTenantIdAndStatus(tenant, "PENDING"));
                forwarder.forwardBatch(slow, () -> { });
                assertEquals(32, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
            } finally { release.countDown(); }
        }
    }
}
