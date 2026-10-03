package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestPreviewServiceTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void previewsTheLiveNormalizerAndRestoresServerOwnedSourceAfterRuleOutput() {
        TenantContext.set("tenant-a");
        var source = LogSource.create("source", SourceType.FILE, ParseFormat.JSON, "/logs/app", null, null, "local", false);
        var references = mock(ReferenceSetStore.class);
        var parsers = mock(ParserRegistry.class);
        var resolver = mock(IngestSourceResolver.class);
        var pipeline = mock(ParsePipelineResolver.class);
        var context = new IngestSourceContext(source.id(), source.id(), ParseFormat.JSON, List.of("rule"), null, "UTC", true);
        when(references.snapshot()).thenReturn(ReferenceSetStore.Snapshot.EMPTY);
        when(resolver.resolve(anyString(), eq(source.id()))).thenReturn(context);
        when(parsers.parse(anyString(), eq(ParseFormat.JSON), isNull())).thenReturn(Map.of("message", "sample", "source_id", "spoofed-input"));
        when(pipeline.apply(eq(context), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ParsePipelineResolver.Result(true, "rule", Map.of("source_id", "spoofed-rule", "host", "real-host"), null));
        when(pipeline.version(context)).thenReturn("version-1");
        var normalizer = new IngestEventNormalizer(references, parsers, resolver, pipeline);
        var result = new IngestPreviewService(normalizer).preview(source, "{\"message\":\"sample\"}");
        assertEquals(true, result.get("ok"));
        assertEquals(false, result.get("writesEvent"));
        assertEquals(false, result.get("mayTriggerDownstreamActions"));
        assertEquals("version-1", result.get("parserVersion"));
        assertEquals(source.id(), ((Map<?, ?>) result.get("fields")).get("source_id"));
        assertEquals("tenant-a", ((Map<?, ?>) result.get("fields")).get("tenant_id"));
        assertEquals("rule", result.get("ruleId"));
        verify(resolver, atLeastOnce()).resolve(anyString(), eq(source.id()));
    }

    @Test void parseFailureIsAReadOnlyPreviewResult() {
        var source = LogSource.create("draft", SourceType.FILE, ParseFormat.AUTO, null, null, null, null, false);
        var normalizer = mock(IngestEventNormalizer.class);
        when(normalizer.lookupSnapshot()).thenReturn(ReferenceSetStore.Snapshot.EMPTY);
        when(normalizer.normalize(eq("broken"), eq(source.id()), eq("preview"), any()))
                .thenThrow(new IngestParseException("invalid timestamp"));
        var result = new IngestPreviewService(normalizer).preview(source, "broken");
        assertEquals(false, result.get("ok"));
        assertEquals(false, result.get("writesEvent"));
        assertEquals("invalid timestamp", result.get("error"));
    }
}
