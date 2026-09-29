package com.socp.ai.service;

import com.socp.ai.config.InvestigationProperties;
import com.socp.ai.infrastructure.llm.LlmChatClient;
import com.socp.ai.persistence.repository.InvestigationRepository;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InvestigationQueuePersistenceTest {
    @Autowired InvestigationRepository repository;
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void acceptedReceiptIsImmediatelyReadableAndLateAttemptCannotOverwriteNewOwner() {
        TenantContext.set("audit-ai-queue");
        var agent = new InvestigationAgentService(repository, mock(AlertClient.class), mock(SearchClient.class),
                mock(IncidentClient.class), mock(ThreatClient.class), mock(LlmChatClient.class),
                mock(AuditSink.class), new InvestigationProperties());
        var service = new AsyncInvestigationJobService(agent, repository, 1);
        try {
            var first = service.submit("  AL-QUEUE  ");
            String id = (String) first.get("jobId");
            assertEquals("AL-QUEUE", first.get("alertId"));
            assertEquals("NEW", agent.get(id).get("status"));
            assertEquals(id, service.submit("AL-QUEUE").get("jobId"));
            assertTrue(repository.findRecoverable(Instant.now(), PageRequest.of(0, 100)).stream()
                    .anyMatch(row -> id.equals(row.getId())));
            Instant now = Instant.now();
            assertEquals(1, repository.claim(id, "audit-ai-queue", "old", now.minusSeconds(30), now.minusSeconds(1)));
            assertEquals(1, repository.claim(id, "audit-ai-queue", "new", now, now.plusSeconds(60)));
            assertEquals(0, repository.complete(id, "audit-ai-queue", "old", "COMPLETED", "{}", now));
            assertEquals(0, repository.fail(id, "audit-ai-queue", "old", "{}", now));
            assertEquals(1, repository.complete(id, "audit-ai-queue", "new", "PARTIAL", "{}", now));
            assertEquals(0, repository.claim(id, "audit-ai-queue", "third", now, now.plusSeconds(60)));
            assertEquals("PARTIAL", agent.get(id).get("status"));
        } finally {
            service.close();
        }
    }

    @Test
    void onlyOneDifferentIncidentTargetCanOwnTheAppendSideEffect() throws Exception {
        TenantContext.set("audit-ai-append");
        var row = new com.socp.ai.persistence.entity.InvestigationEntity();
        row.setId("append-race");
        row.setTenantId("audit-ai-append");
        row.setAlertId("AL-APPEND");
        row.setStatus("COMPLETED");
        row.setResultJson("{}");
        row.setAppendClaimToken("expired-owner");
        row.setAppendClaimUntil(Instant.now().minusSeconds(1));
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        repository.saveAndFlush(row);

        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> claimAppend(start, "CASE-A", "token-a"));
            var b = pool.submit(() -> claimAppend(start, "CASE-B", "token-b"));
            start.countDown();
            int claimedA = a.get();
            int claimedB = b.get();
            assertEquals(1, claimedA + claimedB);
            var stored = repository.findByIdAndTenantId("append-race", "audit-ai-append").orElseThrow();
            assertEquals(claimedA == 1 ? "CASE-A" : "CASE-B", stored.getIncidentId());
            assertEquals(claimedA == 1 ? "token-a" : "token-b", stored.getAppendClaimToken());
            assertTrue(stored.getAppendClaimUntil().isAfter(Instant.now()));
        }
    }

    private int claimAppend(CountDownLatch start, String incidentId, String token) throws Exception {
        start.await();
        try (TenantContext.Scope ignored = TenantContext.open("audit-ai-append")) {
            Instant now = Instant.now();
            return repository.claimAppend("append-race", "audit-ai-append", incidentId,
                    token, now, now.plusSeconds(30));
        }
    }
}
