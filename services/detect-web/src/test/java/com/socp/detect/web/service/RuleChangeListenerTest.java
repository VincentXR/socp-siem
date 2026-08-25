package com.socp.detect.web.service;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuleChangeListenerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void restoresTenantForCacheReload() {
        DetectEngineService engine = mock(DetectEngineService.class);
        org.mockito.Mockito.doAnswer(ignored -> {
            assertEquals("tenant-a", TenantContext.require());
            return null;
        }).when(engine).reload();
        RuleChangeListener listener = new RuleChangeListener(engine);

        listener.processRecord("""
                {"eventId":"event-1","tenantId":"tenant-a","ruleId":"rule-7","action":"update"}
                """);

        verify(engine).reload();
        assertNull(TenantContext.get());
    }

    @Test
    void rejectsMissingOrInvalidTenant() {
        DetectEngineService engine = mock(DetectEngineService.class);
        RuleChangeListener listener = new RuleChangeListener(engine);

        assertThrows(RuleChangeListener.InvalidRuleChangeException.class,
                () -> listener.processRecord("""
                        {"eventId":"event-1","tenantId":"../other","ruleId":"rule-7","action":"update"}
                        """));

        verify(engine, never()).reload();
    }
}
