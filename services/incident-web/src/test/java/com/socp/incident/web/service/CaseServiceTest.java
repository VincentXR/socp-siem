package com.socp.incident.web.service;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.incident.web.persistence.repository.AlarmCaseLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.socp.platform.tenant.context.TenantContext;

@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Mock
    private CaseStore store;

    @Mock
    private AlarmCaseLinkRepository alarmLinks;

    @Test
    void createsCaseForFirstAlarmOfAnEntity() {
        given(store.openCaseId("203.0.113.10")).willReturn(null);
        CaseService service = new CaseService(store, alarmLinks);

        Map<String, Object> result = service.fromAlarm(alarm("AL-1"));

        assertTrue((Boolean) result.get("created"));
        assertEquals(1, result.get("alarmCount"));
        verify(store).save(any(Case.class));
    }

    @Test
    void mergesNewAlarmIntoExistingOpenCase() {
        Case existing = Case.create("existing", "203.0.113.10", "HIGH")
                .withAdded("AUTH-BRUTE", "AL-1",
                        new com.socp.incident.web.domain.TimelineEvent(
                                java.time.Instant.now(), "ALARM", "initial", "detection", "AL-1"));
        given(store.openCaseId("203.0.113.10")).willReturn(existing.id());
        given(store.get(existing.id())).willReturn(existing);
        CaseService service = new CaseService(store, alarmLinks);

        Map<String, Object> result = service.fromAlarm(alarm("AL-2"));

        assertEquals(existing.id(), result.get("caseId"));
        assertEquals(2, result.get("alarmCount"));
        assertTrue(!(Boolean) result.get("created"));
        verify(store).save(any(Case.class));
    }

    @Test
    void duplicateAlarmIsIdempotent() {
        Case existing = Case.create("existing", "203.0.113.10", "HIGH")
                .withAdded("AUTH-BRUTE", "AL-1",
                        new com.socp.incident.web.domain.TimelineEvent(
                                java.time.Instant.now(), "ALARM", "initial", "detection", "AL-1"));
        given(store.openCaseId("203.0.113.10")).willReturn(existing.id());
        given(store.get(existing.id())).willReturn(existing);
        CaseService service = new CaseService(store, alarmLinks);

        Map<String, Object> result = service.fromAlarm(alarm("AL-1"));

        assertTrue((Boolean) result.get("duplicate"));
        assertEquals(1, result.get("alarmCount"));
        verify(store, never()).save(any(Case.class));
    }

    @Test
    void createsManualCaseWithOpenStatusAndAssignee() {
        given(store.save(any(Case.class))).willAnswer(inv -> inv.getArgument(0));
        CaseService service = new CaseService(store, alarmLinks);

        Case created = service.create("Manual investigation", "10.0.0.8", "critical", "analyst");

        assertEquals("Manual investigation", created.title());
        assertEquals("10.0.0.8", created.entity());
        assertEquals("CRITICAL", created.severity());
        assertEquals("OPEN", created.status());
        assertEquals("analyst", created.assignee());
        verify(store).save(any(Case.class));
    }

    @Test
    void computesStatsWithDatabaseCounts() {
        given(store.count()).willReturn(5L);
        // One of the three un-closed rows is CONTAINED (real work in progress),
        // and one legacy row carries a pre-validation junk status; neither may
        // silently inflate "resolved".
        given(store.countByStatusIn(List.of("OPEN", "INVESTIGATING", "CONTAINED"))).willReturn(3L);
        given(store.countByStatusIn(List.of("RESOLVED", "CLOSED"))).willReturn(2L);
        CaseService service = new CaseService(store, alarmLinks);

        Map<String, Object> stats = service.stats();

        assertEquals(5L, stats.get("total"));
        assertEquals(3L, stats.get("open"));
        assertEquals(2L, stats.get("resolved"));
    }

    @Test
    void statusWritesRejectValuesOutsideTheDocumentedLifecycle() {
        given(store.get("case-1")).willReturn(Case.create("case-1", "10.0.0.8", "HIGH"));
        CaseService service = new CaseService(store, alarmLinks);

        assertThatThrownBy(() -> service.setStatus("case-1", "FIXED_LATER", null))
                .isInstanceOf(com.socp.platform.error.exception.ApiException.class)
                .hasFieldOrPropertyWithValue("code", 400);
        verify(store, never()).save(any(Case.class));

        service.setStatus("case-1", "CONTAINED", "analyst");
        verify(store).save(any(Case.class));
    }

    @Test
    void missingCaseWritesFailLoudlyInsteadOfReturningErrorData() {
        given(store.get("missing")).willReturn(null);
        CaseService service = new CaseService(store, alarmLinks);

        for (java.util.function.Supplier<Map<String, Object>> call : List.<java.util.function.Supplier<Map<String, Object>>>of(
                () -> service.setStatus("missing", "RESOLVED", null),
                () -> service.addNote("missing", "analyst", "note", null),
                () -> service.timeline("missing", 0, 50))) {
            assertThatThrownBy(call::get)
                    .isInstanceOf(com.socp.platform.error.exception.ApiException.class)
                    .hasFieldOrPropertyWithValue("code", 404);
        }
        verify(store, never()).appendTimeline(any(), any());
        verify(store, never()).save(any(Case.class));
    }

    private static Map<String, Object> alarm(String id) {
        return Map.of(
                "id", id,
                "ruleId", "AUTH-BRUTE",
                "ruleName", "SSH brute force",
                "severity", "HIGH",
                "entity", "203.0.113.10",
                "message", "failed login",
                "occurredAt", "2026-08-15T00:00:00Z");
    }
}
