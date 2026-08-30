package com.socp.search.config.api.controller;

import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.service.IngestPipeline;
import com.socp.search.config.service.IngestTaskMonitor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(store.list()).thenReturn(List.of(file, socket, kafka, empty));
        when(monitor.runtime(anyString(), anyBoolean())).thenReturn(Map.of("health", "HEALTHY"));

        IngestTaskController controller = new IngestTaskController(store, monitor, pipeline);

        List<Map<String, Object>> tasks = controller.tasks();

        assertThat(tasks).hasSize(4);
        assertThat(tasks.get(0)).containsEntry("target", "/var/log/auth.log");
        assertThat(tasks.get(1)).containsEntry("target", "tcp://127.0.0.1:5514");
        assertThat(tasks.get(2)).containsEntry("target", "kafka:socp-events");
        assertThat(tasks.get(3)).containsEntry("target", "-");
        assertThat(tasks.get(0)).containsEntry("runtime", Map.of("health", "HEALTHY"));
    }

    @Test
    void combinesConfigurationAndRuntimeSummary() {
        LogSource enabled = source("auth", SourceType.FILE, "/var/log/auth.log", null, null, true);
        LogSource disabled = source("audit", SourceType.FILE, "/var/log/audit.log", null, null, false);
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.list()).thenReturn(List.of(enabled, disabled));
        when(store.enabled()).thenReturn(List.of(enabled));
        when(monitor.summary(List.of(enabled.collectorTag())))
                .thenReturn(Map.of("accepted", 3L));

        Map<String, Object> summary = new IngestTaskController(store, monitor, pipeline).summary();

        assertThat(summary).containsEntry("accepted", 3L)
                .containsEntry("sources", 2)
                .containsEntry("enabledSources", 1);
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

        assertThat(started.getStatusCode().value()).isEqualTo(200);
        assertThat(started.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", true);
        assertThat(stopped.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", false);
        assertThat(tested.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("ok", true);
        assertThat(custom.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sample", "custom raw");
        verify(store, org.mockito.Mockito.times(2)).save(any(LogSource.class));
        verify(pipeline).process("custom raw", source.collectorTag());
    }

    @Test
    void returnsNotFoundForUnknownTaskIds() {
        LogSourceStore store = mock(LogSourceStore.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        IngestPipeline pipeline = mock(IngestPipeline.class);
        when(store.get("missing")).thenReturn(Optional.empty());
        IngestTaskController controller = new IngestTaskController(store, monitor, pipeline);

        assertThat(controller.task("missing").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.start("missing").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.stop("missing").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.test("missing", null).getStatusCode().value()).isEqualTo(404);
    }

    private static LogSource source(String name, SourceType type, String path,
                                    String address, String topic, boolean enabled) {
        return LogSource.createFull(name, type, ParseFormat.SYSLOG, path, address, topic,
                "prod", enabled, "beginning", null, null, List.of(), "description",
                type == SourceType.SOCKET ? "tcp" : null, "utf-8", "event_time", "UTC",
                List.of("team=security"), 5, "AUTH", "group-1");
    }
}
