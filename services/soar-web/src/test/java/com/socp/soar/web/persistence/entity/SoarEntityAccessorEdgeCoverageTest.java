package com.socp.soar.web.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge-case accessor coverage for SOAR entities not exercised by
 * {@link SoarEntityAccessorCoverageTest}: approval-decision getters,
 * trigger-receipt timestamps, run-event trace id and Persistable#isNew
 * on the versioned connector/automation-rule entities.
 */
class SoarEntityAccessorEdgeCoverageTest {

    private static final Instant NOW = Instant.now();

    @Test
    void approvalDecisionEntityRoundTrip() {
        SoarApprovalDecisionEntity entity = new SoarApprovalDecisionEntity();

        entity.setId("dec-1");
        entity.setTenantId("tenant-a");
        entity.setApprovalId("appr-1");
        entity.setActorId("alice");
        entity.setDecision("APPROVED");
        entity.setReason("containment approved");
        entity.setCreatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("dec-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getApprovalId()).isEqualTo("appr-1");
        assertThat(entity.getActorId()).isEqualTo("alice");
        assertThat(entity.getDecision()).isEqualTo("APPROVED");
        assertThat(entity.getReason()).isEqualTo("containment approved");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void triggerReceiptEntityRoundTripIncludingUpdatedAt() {
        SoarTriggerReceiptEntity entity = new SoarTriggerReceiptEntity();

        entity.setId("receipt-1");
        entity.setTenantId("tenant-a");
        entity.setEventId("evt-1");
        entity.setAutomationRuleId("rule-1");
        entity.setRuleRevision(3);
        entity.setStatus("TRIGGERED");
        entity.setRunId("run-1");
        entity.setReason("matched");
        entity.setGroupKey("group-1");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("receipt-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getEventId()).isEqualTo("evt-1");
        assertThat(entity.getAutomationRuleId()).isEqualTo("rule-1");
        assertThat(entity.getRuleRevision()).isEqualTo(3);
        assertThat(entity.getStatus()).isEqualTo("TRIGGERED");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getReason()).isEqualTo("matched");
        assertThat(entity.getGroupKey()).isEqualTo("group-1");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void runEventEntityRoundTripIncludingTraceId() {
        SoarRunEventEntity entity = new SoarRunEventEntity();

        entity.setId("event-1");
        entity.setTenantId("tenant-a");
        entity.setRunId("run-1");
        entity.setNodeRunId("node-run-1");
        entity.setSequenceNo(7L);
        entity.setEventType("NODE_STARTED");
        entity.setActor("system");
        entity.setSummary("node started");
        entity.setDetailJson("{}");
        entity.setTraceId("trace-1");
        entity.setCreatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("event-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getNodeRunId()).isEqualTo("node-run-1");
        assertThat(entity.getSequenceNo()).isEqualTo(7L);
        assertThat(entity.getEventType()).isEqualTo("NODE_STARTED");
        assertThat(entity.getActor()).isEqualTo("system");
        assertThat(entity.getSummary()).isEqualTo("node started");
        assertThat(entity.getDetailJson()).isEqualTo("{}");
        assertThat(entity.getTraceId()).isEqualTo("trace-1");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void connectorEntityIsNewDependsOnRowVersion() {
        SoarConnectorEntity entity = new SoarConnectorEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void automationRuleEntityIsNewDependsOnRowVersion() {
        SoarAutomationRuleEntity entity = new SoarAutomationRuleEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setRowVersion(4L);
        assertThat(entity.getRowVersion()).isEqualTo(4L);
        assertThat(entity.isNew()).isFalse();
    }
}
