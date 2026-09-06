package com.socp.soar.web.api.controller;

import com.socp.platform.error.api.ApiResult;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.service.SoarV2AutomationRuleService;
import com.socp.soar.web.service.SoarV2ConnectorService;
import com.socp.soar.web.service.SoarV2Service;
import com.socp.soar.web.service.SoarV2TemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Edge coverage for the V2 controller: pagination alias, SSE stream, coercions. */
@ExtendWith(MockitoExtension.class)
class SoarV2ControllerEdgeCoverageTest {

    @Mock
    private SoarV2Service service;
    @Mock
    private SoarV2AutomationRuleService automationRules;
    @Mock
    private SoarV2ConnectorService connectors;
    @Mock
    private SoarV2TemplateService templates;

    private SoarV2Controller controller;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        controller = new SoarV2Controller(service, automationRules, connectors, templates);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void artifactListingPaginatesWhenPageOrSizeIsPresent() {
        given(service.listArtifacts(eq("run-1"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "a-1")), PageRequest.of(0, 20), 1));
        given(service.listArtifacts("run-1")).willReturn(List.of(Map.of("id", "a-1")));

        ApiResult<Object> paged = controller.artifacts("run-1", 0, 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> pageView = (Map<String, Object>) paged.data();
        assertThat(pageView).containsEntry("page", 0)
                .containsEntry("size", 20)
                .containsEntry("total", 1L);
        verify(service).listArtifacts(eq("run-1"), any(Pageable.class));

        ApiResult<Object> plain = controller.artifacts("run-1", null, null);
        assertThat((List<?>) plain.data()).hasSize(1);
        verify(service).listArtifacts("run-1");
    }

    @Test
    void connectionListWithoutPaginationUsesCompatibilityAlias() {
        given(connectors.list()).willReturn(List.of(Map.of("id", "c1")));

        ApiResult<Object> result = controller.connections(null, null);

        assertThat((List<?>) result.data()).hasSize(1);
        verify(connectors).list();
    }

    @Test
    void eventStreamSendsEventsAndAdvancesCursorFromLastEventId() {
        given(service.listEvents(eq("run-1"), anyLong(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(Map.of("sequence", 7L, "type", "NODE_SUCCEEDED")),
                        PageRequest.of(0, 100), 1));

        // A non-numeric Last-Event-ID is treated as sequence 0.
        SseEmitter emitter = controller.stream("run-1", "abc");

        verify(service, timeout(5_000).atLeastOnce())
                .listEvents(eq("run-1"), anyLong(), any(Pageable.class));
        emitter.complete();
    }

    @Test
    void eventStreamCompletesWithErrorWhenEventQueryFails() throws Exception {
        // Hold the first poll until the stream is observed: the poller runs on
        // a scheduler thread, so the failure window is racy without a gate.
        CountDownLatch failureGate = new CountDownLatch(1);
        given(service.listEvents(anyString(), anyLong(), any(Pageable.class)))
                .willAnswer(invocation -> {
                    failureGate.await(5, TimeUnit.SECONDS);
                    throw new IllegalStateException("db down");
                });

        SseEmitter emitter = controller.stream("run-1", null);
        failureGate.countDown();
        verify(service, timeout(5_000).atLeastOnce())
                .listEvents(eq("run-1"), anyLong(), any(Pageable.class));

        // A failed poll must complete the emitter. A completed emitter rejects
        // further sends with IllegalStateException; onError/onCompletion
        // callbacks are fired by the MVC async handler, which does not exist
        // in a bare unit test, so completion is probed directly.
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            try {
                emitter.send(SseEmitter.event().comment("probe"));
            } catch (IllegalStateException expected) {
                return;
            }
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchConnectionFallsBackToCurrentValuesAndNullRowVersionOnBadNumber() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("name", "conn");
        current.put("connectorType", "http.webhook");
        current.put("endpoint", "https://hooks.example.test/x");
        current.put("allowedHosts", List.of("hooks.example.test"));
        current.put("enabled", true);
        given(connectors.get("c1")).willReturn(current);
        given(connectors.update(eq("c1"), eq("conn"), eq("http.webhook"),
                eq("https://hooks.example.test/x"), isNull(), eq(List.of("hooks.example.test")),
                eq(true), isNull()))
                .willReturn(Map.of("id", "c1"));

        ApiResult<Map<String, Object>> result =
                controller.patchConnection("c1", Map.<String, Object>of("rowVersion", "abc"));

        assertThat(result.code()).isZero();
        assertThat(result.data()).containsEntry("id", "c1");
    }
}
