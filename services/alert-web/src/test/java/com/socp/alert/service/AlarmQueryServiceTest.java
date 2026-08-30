package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.Severity;
import com.socp.alert.repository.AlarmRepository;
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
        when(repository.list(eq("tenant-a"), any(AlarmQuery.class))).thenReturn(List.of());

        service.query(Severity.HIGH, "  R-1 ", " OPEN ", " login ", "unknown", "ASC");

        ArgumentCaptor<AlarmQuery> query = ArgumentCaptor.forClass(AlarmQuery.class);
        verify(repository).list(eq("tenant-a"), query.capture());
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
