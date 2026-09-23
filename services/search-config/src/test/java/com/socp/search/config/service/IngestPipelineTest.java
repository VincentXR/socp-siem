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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class IngestPipelineTest {

    @Test
    void hipsTransportKeyKeepsFalcoIdentityStableWhenVendorParserDropsEnvelopeId() throws Exception {
        var normalizer = new IngestEventNormalizer(null, null, null,
                new com.socp.search.config.parser.ParserRegistry());
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        when(monitor.runtime("service:hips-web", true)).thenReturn(Map.of("eps1m", 0.0));
        var committed = new java.util.ArrayList<SearchEvent>();
        when(commit.commit(anyList())).thenAnswer(invocation -> {
            List<SearchEvent> batch = invocation.getArgument(0);
            committed.addAll(batch);
            return new IngestionCommitService.CommitResult(1, 1, 0, 0);
        });
        IngestPipeline pipeline = new IngestPipeline(normalizer, commit, monitor,
                mock(DetectClient.class), new SimpleMeterRegistry());
        String body;
        try (var fixture = getClass().getResourceAsStream("/fixtures/falco-native-event.json")) {
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture);
            json.put("eventId", "stored-event-1").put("tenantId", "spoofed");
            body = json.toString();
        }
        com.socp.platform.tenant.context.TenantContext.runWith("tenant-a", () -> {
            assertEquals(1, pipeline.process(body, "service:hips-web", "hips:stored-event-1")
                    .get("acknowledged"));
            pipeline.process(body, "service:hips-web", "hips:stored-event-1");
            pipeline.process(body, "service:hips-web", "hips:stored-event-2");
        });

        assertEquals(3, committed.size());
        assertEquals(committed.get(0).eventId(), committed.get(1).eventId());
        assertEquals(IngestionEventIdentity.fingerprint(committed.get(0)),
                IngestionEventIdentity.fingerprint(committed.get(1)));
        assertNotEquals(committed.get(0).eventId(), committed.get(2).eventId());
        for (SearchEvent event : committed) {
            assertEquals("tenant-a", event.fields().get("tenant_id"));
            assertEquals("service:hips-web", event.fields().get("collector"));
            assertEquals("Synthetic process event for ingestion verification", event.msg());
            assertEquals("falco", event.source());
            assertEquals(Instant.parse("2026-09-22T12:30:45.123456789Z"), event.timestamp());
            assertEquals("bash", event.ecs().get("process.name"));
            assertEquals("alice", event.ecs().get("user.name"));
            assertEquals("1001", event.ecs().get("user.id"));
            assertEquals("syscall", event.ecs().get("falco.source"));
            assertEquals("host", event.fields().get("detection_routing_field"));
            assertEquals("fixture-host", event.fields().get("detection_routing_value"));
        }
    }

    @Test
    void commitsNormalizedBatchOnceAndReportsCollectorCounters() {
        IngestEventNormalizer normalizer = mock(IngestEventNormalizer.class);
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        SearchEvent first = event("event-1");
        SearchEvent second = event("event-2");
        when(normalizer.normalize(eq("one"), eq("collector-1"), any(), any()))
                .thenReturn(new IngestEventNormalizer.NormalizedEvent(first, Map.of("eventId", "event-1"), "collector-1"));
        when(normalizer.normalize(eq("two"), eq("collector-1"), any(), any()))
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
        when(normalizer.normalize(anyString(), eq("collector-1"), any(), any()))
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

    @Test
    void sourceResolutionFailureIs503InsteadOfAParseSkip() {
        IngestEventNormalizer normalizer = mock(IngestEventNormalizer.class);
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        when(normalizer.normalize(eq("event"), eq("collector-1"), any(), any()))
                .thenThrow(new IllegalStateException("source database unavailable"));
        when(monitor.runtime("collector-1", true)).thenReturn(Map.of("eps1m", 0.0));
        IngestPipeline pipeline = new IngestPipeline(normalizer, commit, monitor,
                mock(DetectClient.class), new SimpleMeterRegistry());

        ApiException failure = assertThrows(ApiException.class,
                () -> pipeline.process("event", "collector-1"));

        assertEquals(503, failure.getCode());
        verify(monitor).record(eq("collector-1"), eq(0), eq(0), eq(0), anyLong());
    }

    @Test
    void expectedLineBudgetFailureIsCountedAsAParseSkip() {
        IngestEventNormalizer normalizer = mock(IngestEventNormalizer.class);
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        when(normalizer.normalize(eq("event"), eq("collector-1"), any(), any()))
                .thenThrow(new IngestParseException("event contains too many fields"));
        when(monitor.runtime("collector-1", true)).thenReturn(Map.of("eps1m", 0.0));
        IngestPipeline pipeline = new IngestPipeline(normalizer, commit, monitor,
                mock(DetectClient.class), new SimpleMeterRegistry());

        Map<String, Object> result = pipeline.process("event", "collector-1");

        assertEquals(0, result.get("accepted"));
        assertEquals(1, result.get("skipped"));
        verify(monitor).record(eq("collector-1"), eq(0), eq(1), eq(0), anyLong());
    }

    @Test
    void idempotencyFingerprintChangesWhenSameKeyCarriesDifferentPayload() {
        IngestEventNormalizer normalizer = mock(IngestEventNormalizer.class);
        IngestionCommitService commit = mock(IngestionCommitService.class);
        IngestTaskMonitor monitor = mock(IngestTaskMonitor.class);
        when(monitor.runtime("collector-1", true)).thenReturn(Map.of("eps1m", 0.0));
        when(normalizer.normalize(anyString(), eq("collector-1"), anyString(), any()))
                .thenAnswer(invocation -> {
                    String line = invocation.getArgument(0, String.class);
                    return new IngestEventNormalizer.NormalizedEvent(
                            event(line), Map.of("eventId", line), "collector-1");
                });
        IngestPipeline pipeline = new IngestPipeline(normalizer, commit, monitor,
                mock(DetectClient.class), new SimpleMeterRegistry());

        pipeline.process("same", "collector-1", "request-1");
        pipeline.process("different", "collector-1", "request-1");

        org.mockito.ArgumentCaptor<String> identities = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(normalizer, times(2)).normalize(anyString(), eq("collector-1"), identities.capture(), any());
        assertNotEquals(identities.getAllValues().get(0), identities.getAllValues().get(1));
    }

    private static SearchEvent event(String id) {
        return new SearchEvent(id, Instant.EPOCH, "auth", "host", "HIGH", "raw",
                Map.of("tenant_id", "tenant-a"), Map.of());
    }
}
