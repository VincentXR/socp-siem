package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.domain.PlaybookStatus;
import com.socp.soar.web.persistence.store.PlaybookStore;
import com.socp.soar.web.persistence.store.ScheduledPlaybookRunStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduledPlaybookRunnerTest {

    @Mock
    private PlaybookStore store;
    @Mock
    private PlaybookExecutor executor;
    @Mock
    private ScheduledPlaybookRunStore runStore;

    @BeforeEach
    void clearTenantBeforeEach() {
        // The scheduler must not inherit a tenant left by another test or a
        // Spring test context. Each test establishes its own caller scope.
        TenantContext.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void scansEveryTenantInsideItsOwnScopeAndRestoresCallerContext() {
        Playbook playbook = scheduled("pb-1", "schedule daily 03:30");
        given(store.tenantsWithEnabledPlaybooks()).willReturn(List.of("tenant-a", "tenant-b"));
        given(store.list()).willAnswer(invocation -> {
            String tenant = TenantContext.require();
            return List.of(playbook);
        });
        given(runStore.claim(anyString(), eq("pb-1"), any())).willAnswer(invocation -> {
            String tenant = invocation.getArgument(0);
            Instant fire = invocation.getArgument(2);
            return new ScheduledPlaybookRunStore.Claim("claim-" + tenant, tenant, "pb-1", fire);
        });
        given(executor.runById(eq("pb-1"), anyMap())).willReturn(Map.of("status", "SUCCESS"));

        TenantContext.set("caller");
        runner().tick(ZonedDateTime.of(2026, 8, 29, 3, 30, 42, 0, ZoneOffset.UTC));

        assertEquals("caller", TenantContext.require());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contexts = ArgumentCaptor.forClass(Map.class);
        verify(executor, times(2)).runById(eq("pb-1"), contexts.capture());
        assertEquals(List.of("tenant-a", "tenant-b"), contexts.getAllValues().stream()
                .map(context -> String.valueOf(context.get("tenantId"))).toList());
        verify(runStore, times(2)).complete(any());
    }

    @Test
    void honorsTheConfiguredMinuteInsteadOfFiringAtTheTopOfTheHour() {
        given(store.tenantsWithEnabledPlaybooks()).willReturn(List.of("default"));
        given(store.list()).willReturn(List.of(scheduled("pb-1", "schedule daily 15:30")));

        runner().tick(ZonedDateTime.of(2026, 8, 29, 15, 0, 0, 0, ZoneOffset.UTC));

        verify(runStore, never()).claim(anyString(), anyString(), any());
        verify(executor, never()).runById(anyString(), anyMap());
        assertNull(TenantContext.get());
    }

    @Test
    void duplicateDatabaseClaimSkipsTheSideEffect() {
        given(store.tenantsWithEnabledPlaybooks()).willReturn(List.of("default"));
        given(store.list()).willReturn(List.of(scheduled("pb-1", "schedule daily 03:30")));
        given(runStore.claim(anyString(), anyString(), any())).willReturn(null);

        runner().tick(ZonedDateTime.of(2026, 8, 29, 3, 30, 0, 0, ZoneOffset.UTC));

        verify(executor, never()).runById(anyString(), anyMap());
        assertNull(TenantContext.get());
    }

    @Test
    void oneBrokenTenantDoesNotStarveTheRemainingTenants() {
        Playbook playbook = scheduled("pb-2", "schedule daily 03:30");
        given(store.tenantsWithEnabledPlaybooks()).willReturn(List.of("broken", "healthy"));
        given(store.list()).willAnswer(invocation -> {
            if ("broken".equals(TenantContext.require())) throw new IllegalStateException("broken data");
            return List.of(playbook);
        });
        given(runStore.claim(eq("healthy"), eq("pb-2"), any())).willAnswer(invocation ->
                new ScheduledPlaybookRunStore.Claim("healthy-claim", "healthy", "pb-2",
                        invocation.getArgument(2)));
        given(executor.runById(eq("pb-2"), anyMap())).willReturn(Map.of());

        runner().tick(ZonedDateTime.of(2026, 8, 29, 3, 30, 0, 0, ZoneOffset.UTC));

        verify(executor).runById(eq("pb-2"), anyMap());
        assertNull(TenantContext.get());
    }

    private ScheduledPlaybookRunner runner() {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();
        properties.setScheduleZone("UTC");
        return new ScheduledPlaybookRunner(store, executor, runStore, properties);
    }

    private static Playbook scheduled(String id, String trigger) {
        return new Playbook(id, "scheduled", trigger, List.of("simulate:report"), true,
                PlaybookStatus.ACTIVE, Instant.EPOCH);
    }
}
