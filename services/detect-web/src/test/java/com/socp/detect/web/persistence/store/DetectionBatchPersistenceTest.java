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
    @Test void claimsAndCompletionAreBatchedAndTerminalReceiptIsImmutable() {
        var input=inputs();var stats=factory.unwrap(SessionFactory.class).getStatistics();stats.clear();
        assertTrue(journal.claimBatch(input).stream().allMatch(c->c==DetectionEventClaim.NEW));
        assertTrue(stats.getPrepareStatementCount()<=4,"claim prepared "+stats.getPrepareStatementCount());
        stats.clear();forwarder.forwardBatch(results(input),()->{});
        assertTrue(stats.getPrepareStatementCount()<=4,"completion prepared "+stats.getPrepareStatementCount());
        assertEquals(input.stream().map(i->i.event().id()).toList(),
                repository.findByTenantIdAndStatusAndCompletedAtAfterOrderByCompletedAt(tenant,"COMPLETED",
                        Instant.now().minusSeconds(60),org.springframework.data.domain.PageRequest.of(0,100))
                        .stream().map(row->row.getDeliveryId()).toList(), "recovery must retain evaluation order");
        var first=repository.findByTenantIdAndDeliveryId(tenant,"event-0").orElseThrow();
        assertEquals("COMPLETED",first.getStatus());assertNotEquals("{}",first.getResultJson());
        forwarder.forwardBatch(results(input),()->{});
        assertEquals(first.getCompletedAt(),repository.findByTenantIdAndDeliveryId(tenant,"event-0").orElseThrow().getCompletedAt());
        assertTrue(journal.claimBatch(input).stream().allMatch(c->c==DetectionEventClaim.COMPLETED));
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
