package com.socp.detect.web.service;

import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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
}
