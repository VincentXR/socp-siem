package com.socp.detect.web.service;

import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuleChangePublisherTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void enqueuesTenantScopedChangeInsteadOfSendingInline() {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        TenantContext.set("tenant-a");

        publisher.publish("rule-7", "update");

        ArgumentCaptor<RuleChangeOutbox> row = ArgumentCaptor.forClass(RuleChangeOutbox.class);
        verify(repository).save(row.capture());
        assertEquals("tenant-a", row.getValue().getTenantId());
        assertEquals("rule-7", row.getValue().getRuleId());
        assertEquals("PENDING", row.getValue().getStatus());
    }

    @Test
    void retryLimitMovesRuleChangeToDeadWithDurableReason() {
        RuleChangeOutboxRepository repository = mock(RuleChangeOutboxRepository.class);
        RuleChangePublisher publisher = new RuleChangePublisher(repository);
        RuleChangeOutbox row = new RuleChangeOutbox();
        row.setId("outbox-1");
        row.setTenantId("tenant-a");
        row.setRuleId("rule-7");
        row.setAttempts(11);

        publisher.scheduleRetry(row, new IllegalStateException("broker unavailable"));

        verify(repository).markDead(eq("outbox-1"),
                eq("IllegalStateException: broker unavailable"), any());
        verify(repository, never()).scheduleRetry(any(), any(), any(), any());
    }
}
