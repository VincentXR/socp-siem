package com.socp.search.config.service;

import com.socp.platform.client.DetectClient;
import com.socp.search.config.search.IngestionCommitService;
import com.socp.search.config.search.SearchEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestPipelineTest {

    @Test
    void commitsNormalizedBatchOnceAndReportsCollectorCounters() {
        IngestEventNormalizer normalizer = mock(IngestEventNormalizer.class);
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        SearchEvent first = event("event-1");
        SearchEvent second = event("event-2");
        when(normalizer.normalize("one", "collector-1"))
                .thenReturn(new IngestEventNormalizer.NormalizedEvent(first, Map.of("eventId", "event-1"), "collector-1"));
        when(normalizer.normalize("two", "collector-1"))
                .thenReturn(new IngestEventNormalizer.NormalizedEvent(second, Map.of("eventId", "event-2"), "collector-1"));
        when(monitor.runtime("collector-1", true)).thenReturn(Map.of("eps1m", 2.0));
        IngestPipeline pipeline = new IngestPipeline(normalizer, commit, monitor,
                mock(DetectClient.class), new SimpleMeterRegistry());

        Map<String, Object> result = pipeline.process("one\ntwo\n", "collector-1");

        verify(commit).commit(List.of(first, second));
        verify(monitor).record(eq("collector-1"), eq(2), eq(0), eq(0), anyLong());
        assertEquals(2, result.get("accepted"));
        assertEquals(0, result.get("skipped"));
    }

    private static SearchEvent event(String id) {
        return new SearchEvent(id, Instant.EPOCH, "auth", "host", "HIGH", "raw",
                Map.of("tenant_id", "tenant-a"), Map.of());
    }
}
