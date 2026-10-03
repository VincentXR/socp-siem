package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmQueryServiceTest {

    private AlarmRepository repository;
    private AlarmQueryService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        repository = mock(AlarmRepository.class);
        service = new AlarmQueryService(repository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void normalizesFiltersAndSortsAscending() {
        when(repository.page(eq("tenant-a"), any(AlarmQuery.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.page(Severity.HIGH, "  R-1 ", " OPEN ", " login ", "unknown", "ASC", 1, 20);

        ArgumentCaptor<AlarmQuery> query = ArgumentCaptor.forClass(AlarmQuery.class);
        verify(repository).page(eq("tenant-a"), query.capture(), any(Pageable.class));
        assertThat(query.getValue().severity()).isEqualTo(Severity.HIGH);
        assertThat(query.getValue().rule()).isEqualTo("R-1");
        assertThat(query.getValue().status()).isEqualTo("OPEN");
        assertThat(query.getValue().text()).isEqualTo("login");
        assertThat(query.getValue().sort()).isEqualTo(AlarmQuery.SortField.OCCURRED_AT);
        assertThat(query.getValue().ascending()).isTrue();
    }

    @Test
    void clampsNegativePagesAndResolvesEverySupportedSortField() {
        when(repository.page(eq("tenant-a"), any(AlarmQuery.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        for (String sort : List.of("severity", "ruleName", "entity", "status", "riskScore", "alertCreatedAt")) {
            service.page(null, null, null, null, sort, "descending", -2, 25);
        }

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, org.mockito.Mockito.times(6))
                .page(eq("tenant-a"), any(AlarmQuery.class), pageable.capture());
        assertThat(pageable.getAllValues()).allSatisfy(value -> {
            assertThat(value.getPageNumber()).isZero();
            assertThat(value.getPageSize()).isEqualTo(25);
        });
    }

    @Test
    void countsUsingTheSameTenantScopedFiltersAsPagedReads() {
        when(repository.count(eq("tenant-a"), any(AlarmQuery.class))).thenReturn(42L);

        long total = service.count(Severity.HIGH, " R-1 ", " OPEN ", " login ",
                "riskScore", "ascending");

        assertThat(total).isEqualTo(42L);
        ArgumentCaptor<AlarmQuery> query = ArgumentCaptor.forClass(AlarmQuery.class);
        verify(repository).count(eq("tenant-a"), query.capture());
        assertThat(query.getValue().severity()).isEqualTo(Severity.HIGH);
        assertThat(query.getValue().rule()).isEqualTo("R-1");
        assertThat(query.getValue().status()).isEqualTo("OPEN");
        assertThat(query.getValue().text()).isEqualTo("login");
        assertThat(query.getValue().sort()).isEqualTo(AlarmQuery.SortField.RISK_SCORE);
        assertThat(query.getValue().ascending()).isTrue();
    }

    @Test
    void investigationAndAssigneeCriteriaComposeWithoutChangingTheEvidenceWindow() {
        var from = java.time.Instant.parse("2026-09-01T00:00:00Z");
        var to = from.plusSeconds(3600);
        when(repository.page(eq("tenant-a"), any(AlarmQuery.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.investigationPage(null, null, "ACTIVE", null, "occurredAt", "descending", 1, 20,
                "alice", " host-a ", from, to, "T1110", "high", " alice ");
        var query = ArgumentCaptor.forClass(AlarmQuery.class);
        verify(repository).page(eq("tenant-a"), query.capture(), any(Pageable.class));
        assertThat(query.getValue().owner()).isEqualTo("alice");
        assertThat(query.getValue().assignee()).isEqualTo("alice");
        assertThat(query.getValue().entity()).isEqualTo("host-a");
        assertThat(query.getValue().inclusiveTo()).isFalse();
        assertThat(query.getValue().severityGroup()).isEqualTo("high");
    }

    @Test
    void returnsTenantScopedAlarmOrAStableNotFoundError() {
        Alarm alarm = new Alarm();
        when(repository.findByTenantIdAndId("tenant-a", "alarm-1")).thenReturn(Optional.of(alarm));
        assertThat(service.get("alarm-1")).isSameAs(alarm);

        when(repository.findByTenantIdAndId("tenant-a", "missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(com.socp.platform.error.exception.ApiException.class)
                .hasMessageContaining("Alarm does not exist");
    }
}
