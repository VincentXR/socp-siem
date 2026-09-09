package com.socp.search.config.service;

import com.socp.platform.client.service.DetectClient;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.domain.SearchEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void persistenceFailureIsNotCountedAsParseSkipAndOnlyCommittedRowsAreAccepted() {
        IngestEventNormalizer normalizer = mock(IngestEventNormalizer.class);
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        when(monitor.runtime("collector-1", true)).thenReturn(Map.of("eps1m", 0.0));
        when(normalizer.normalize(anyString(), eq("collector-1")))
                .thenAnswer(invocation -> {
                    String line = invocation.getArgument(0, String.class);
                    return new IngestEventNormalizer.NormalizedEvent(
                            event(line), Map.of("eventId", line), "collector-1");
                });
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 2) {
                throw new IllegalStateException("database unavailable");
            }
            return null;
        }).when(commit).commit(anyList());
        IngestPipeline pipeline = new IngestPipeline(normalizer, commit, monitor,
                mock(DetectClient.class), new SimpleMeterRegistry());

        String body = String.join("\n", IntStream.range(0, 201)
                .mapToObj(index -> "event-" + index).toList());

        ApiException failure = assertThrows(ApiException.class,
                () -> pipeline.process(body, "collector-1"));

        assertEquals(503, failure.getCode());
        verify(commit, times(2)).commit(anyList());
        verify(monitor).record(eq("collector-1"), eq(200), eq(0), eq(0), anyLong());
    }

    private static SearchEvent event(String id) {
        return new SearchEvent(id, Instant.EPOCH, "auth", "host", "HIGH", "raw",
                Map.of("tenant_id", "tenant-a"), Map.of());
    }
}
