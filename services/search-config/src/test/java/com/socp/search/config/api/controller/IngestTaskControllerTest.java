package com.socp.search.config.api.controller;

import com.socp.platform.error.api.PageResponse;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.service.IngestPipeline;
import com.socp.search.config.service.IngestTaskMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestTaskControllerTest {

    @Test
    void rendersTasksWithTheMostUsefulCollectorTarget() {
        LogSource file = source("file", SourceType.FILE, "/var/log/auth.log", null, null, true);
        LogSource socket = source("socket", SourceType.SOCKET, null, "127.0.0.1:5514", null, true);
        LogSource kafka = source("kafka", SourceType.KAFKA, null, null, "socp-events", false);
        LogSource empty = source("empty", SourceType.HTTP_API, null, null, null, true);
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.page(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(file, socket, kafka, empty)));
        when(monitor.runtime(anyString(), anyBoolean())).thenReturn(Map.of("health", "HEALTHY"));

        IngestTaskController controller = new IngestTaskController(store, monitor, pipeline);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) controller.tasks(null, null).data();

        assertThat(tasks).hasSize(4);
        assertThat(tasks.get(0)).containsEntry("target", "/var/log/auth.log");
        assertThat(tasks.get(1)).containsEntry("target", "tcp://127.0.0.1:5514");
        assertThat(tasks.get(2)).containsEntry("target", "kafka:socp-events");
        assertThat(tasks.get(3)).containsEntry("target", "-");
        assertThat(tasks.get(0)).containsEntry("runtime", Map.of("health", "HEALTHY"));
    }

    @Test
    void taskListingReadsADatabasePageInsteadOfTheWholeTenantCatalogue() {
        LogSource source = source("auth", SourceType.FILE, "/var/log/auth.log", null, null, true);
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.page(PageRequest.of(1, 1))).thenReturn(
                new PageImpl<>(List.of(source), PageRequest.of(1, 1), 2));
        when(monitor.runtime(anyString(), anyBoolean())).thenReturn(Map.of());

        @SuppressWarnings("unchecked")
        PageResponse<Map<String, Object>> page = (PageResponse<Map<String, Object>>)
                new IngestTaskController(store, monitor, pipeline).tasks(2, 1).data();

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isEqualTo(2);
        verify(store, never()).list();
        verify(store, never()).enabled();
    }

    @Test
    void taskListingRejectsOutOfRangePaging() {
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskController controller = new IngestTaskController(store,
                mock(IngestTaskMonitor.class), mock(IngestPipeline.class));

        assertThatThrownBy(() -> controller.tasks(0, 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.tasks(1, 5_000)).isInstanceOf(ApiException.class);
    }

    @Test
    void combinesConfigurationAndRuntimeSummaryWithoutMaterialisingTheCatalogue() {
        LogSource enabled = source("auth", SourceType.FILE, "/var/log/auth.log", null, null, true);
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.enabledCollectorTags()).thenReturn(List.of(enabled.collectorTag()));
        when(store.count()).thenReturn(7L);
        when(store.countEnabled()).thenReturn(1L);
        when(monitor.summary(List.of(enabled.collectorTag())))
                .thenReturn(Map.of("accepted", 3L));

        Map<String, Object> summary = new IngestTaskController(store, monitor, pipeline).summary().data();

        assertThat(summary).containsEntry("accepted", 3L)
                .containsEntry("sources", 7L)
                .containsEntry("enabledSources", 1L);
        verify(store, never()).list();
        verify(store, never()).enabled();
    }

    @Test
    void togglesTasksAndTestsTheConfiguredPipeline() {
        LogSource source = source("auth", SourceType.FILE, "/var/log/auth.log", null, null, false);
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.get(source.id())).thenReturn(Optional.of(source));
        when(monitor.runtime(anyString(), anyBoolean())).thenReturn(Map.of());
        when(pipeline.process(anyString(), eq(source.collectorTag())))
                .thenReturn(Map.of("accepted", 1, "skipped", 0));

        IngestTaskController controller = new IngestTaskController(store, monitor, pipeline);
        var started = controller.start(source.id());
        var stopped = controller.stop(source.id());
        var tested = controller.test(source.id(), null);
        var custom = controller.test(source.id(), new com.socp.search.config.api.request.IngestTestRequest("custom raw"));

        assertThat(started.data()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", true);
        assertThat(stopped.data()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", false);
        assertThat(tested.data()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("ok", true);
        assertThat(custom.data()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sample", "custom raw");
        verify(store, org.mockito.Mockito.times(2)).save(any(LogSource.class));
        verify(pipeline).process("custom raw", source.collectorTag());
    }

    @Test
    void unknownTaskIdsFailClosedOnTheSingleErrorChannel() {
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.get("missing")).thenReturn(Optional.empty());
        IngestTaskController controller = new IngestTaskController(store, monitor, pipeline);

        assertNotFound(() -> controller.task("missing"));
        assertNotFound(() -> controller.start("missing"));
        assertNotFound(() -> controller.stop("missing"));
        assertNotFound(() -> controller.test("missing", null));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(404);
    }

    private static LogSource source(String name, SourceType type, String path,
                                    String address, String topic, boolean enabled) {
        return LogSource.createFull(name, type, ParseFormat.SYSLOG, path, address, topic,
                "prod", enabled, "beginning", null, null, List.of(), "description",
                type == SourceType.SOCKET ? "tcp" : null, "utf-8", "event_time", "UTC",
                List.of("team=security"), 5, "AUTH", "group-1");
    }
}
