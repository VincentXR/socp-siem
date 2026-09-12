package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.parser.CanonicalEvent;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestEventNormalizerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void carriesAuthenticatedTenantIntoCanonicalEvent() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "failed password",
                CanonicalEvent.EVENT_CATEGORY, "authentication",
                CanonicalEvent.HOST_NAME, "host-1",
                CanonicalEvent.SOURCE_IP, "203.0.113.10"));
        when(references.matchedSets(anyString())).thenReturn(java.util.List.of());
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var result = normalizer.normalize("raw event", "collector-1");

        assertEquals("tenant-a", result.event().fields().get("tenant_id"));
        assertEquals("auth", result.event().source());
        assertEquals("collector-1", result.collector());
    }

    @Test
    void authenticatedCollectorCannotBeRelabelledByPayload() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "failed password",
                CanonicalEvent.EVENT_CATEGORY, "authentication",
                "collector", "spoofed-collector"));
        when(references.matchedSets(anyString())).thenReturn(java.util.List.of());
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var result = normalizer.normalize("raw event", "registered-collector");

        assertEquals("registered-collector", result.collector());
        assertEquals("registered-collector", result.event().fields().get("collector"));
    }

    @Test
    void authenticatedTenantCannotBeRelabelledByPayload() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "failed password",
                "tenant_id", "tenant-b",
                "tenantId", "tenant-b"));
        when(references.matchedSets(anyString())).thenReturn(java.util.List.of());
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var result = normalizer.normalize("raw event", "registered-collector");

        assertEquals("tenant-a", result.event().fields().get("tenant_id"));
        assertEquals("tenant-a", result.payload().get("fields") instanceof Map<?, ?> fields
                ? fields.get("tenant_id") : null);
    }

    @Test
    void normalizesVendorSeverityAliasesBeforeKafkaEnvelope() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "failed password",
                CanonicalEvent.EVENT_SEVERITY, "WARNING"));
        when(references.matchedSets(anyString())).thenReturn(java.util.List.of());
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var result = normalizer.normalize("raw event", "collector-1");

        assertEquals("MEDIUM", result.event().severity());
        assertEquals("MEDIUM", result.payload().get("severity"));
        assertEquals("MEDIUM", result.event().fields().get("severity"));
    }

    @Test
    void normalizesEverySeverityFamilyAndFallsBackSafely() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(java.util.List.of());
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        Map<String, String> expected = Map.of(
                "CRITICAL", "CRITICAL",
                "ERROR", "HIGH",
                "WARN", "MEDIUM",
                "DEBUG", "LOW",
                "INFO", "INFO",
                "SEVERE-UNKNOWN", "INFO");
        expected.forEach((input, output) -> {
            when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                    CanonicalEvent.EVENT_MESSAGE, "event",
                    CanonicalEvent.EVENT_SEVERITY, input));
            assertEquals(output, normalizer.normalize("raw event", "collector-1").event().severity());
        });
    }

    @Test
    void requestIdempotencyFallbackIsStableWithoutCollapsingDifferentLines() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "same raw event"));
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var first = normalizer.normalize("same raw event", "collector-1", "batch-42:0");
        var retry = normalizer.normalize("same raw event", "collector-1", "batch-42:0");
        var secondLine = normalizer.normalize("same raw event", "collector-1", "batch-42:1");

        assertEquals(first.event().eventId(), retry.event().eventId());
        assertNotEquals(first.event().eventId(), secondLine.event().eventId());
    }

    @Test
    void missingEventTimeDoesNotMakeAnIdempotentRetryLookLikeDifferentContent() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "event without timestamp"));
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var first = normalizer.normalize("event without timestamp", "collector-1", "batch-42:0");
        var retry = normalizer.normalize("event without timestamp", "collector-1", "batch-42:0");

        assertEquals("true", first.event().fields().get("event_time_generated"));
        assertEquals(IngestionEventIdentity.fingerprint(first.event()),
                IngestionEventIdentity.fingerprint(retry.event()));
    }

    @Test
    void usesCollectorPositionInsteadOfBodyHashForMissingProducerIdentity() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "identical log body",
                "file", "/var/log/auth.log",
                "offset", "42"));
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var first = normalizer.normalize("identical log body", "collector-1");
        var retry = normalizer.normalize("identical log body", "collector-1");
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "identical log body",
                "file", "/var/log/auth.log",
                "offset", "43"));
        var nextLine = normalizer.normalize("identical log body", "collector-1");

        assertEquals(first.event().eventId(), retry.event().eventId());
        assertNotEquals(first.event().eventId(), nextLine.event().eventId());
    }

    @Test
    void rejectsParserOutputWithTooManyFields() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < 513; index++) fields.put("custom_" + index, "value");
        when(parsers.parse(anyString(), anyString())).thenReturn(fields);
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("wide event", "collector-1"));
    }

    @Test
    void rejectsIndexedDimensionsThatWouldOverflowPostgresFacts() {
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());
        when(parsers.parse(anyString(), anyString())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "event",
                "source", "s".repeat(256)));
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                mock(ParsePreviewService.class), mock(ParseRuleStore.class), references, parsers);
        TenantContext.set("tenant-a");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("oversized source", "collector-1"));

        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("source"));
    }

    @Test
    void rejectsFieldsAddedByParsePipelineAfterTheBaseFieldBudget() {
        TenantContext.set("tenant-a");
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());
        ParserRegistry parsers = mock(ParserRegistry.class);
        Map<String, String> base = new LinkedHashMap<>();
        for (int index = 0; index < 510; index++) base.put("base_" + index, "value");
        when(parsers.parse(anyString(), anyString())).thenReturn(base);
        when(parsers.parse(anyString(), any(ParseFormat.class), isNull())).thenReturn(base);
        IngestSourceResolver sourceResolver = mock(IngestSourceResolver.class);
        ParsePipelineResolver pipeline = mock(ParsePipelineResolver.class);
        when(sourceResolver.resolve(anyString(), anyString())).thenReturn(new IngestSourceContext(
                "collector-1", "source-1", ParseFormat.AUTO, List.of("rule-1"), true));
        Map<String, String> additions = new LinkedHashMap<>();
        additions.put("added_1", "value");
        additions.put("added_2", "value");
        additions.put("added_3", "value");
        when(pipeline.apply(any(IngestSourceContext.class), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ParsePipelineResolver.Result(true, "rule-1", additions, null));

        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                references, parsers, sourceResolver, pipeline);

        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("wide event", "collector-1"));
    }

    @Test
    void appliesOnlyRulesBoundToTheResolvedLogSource() {
        TenantContext.set("tenant-a");
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        when(references.matchedSets(anyString())).thenReturn(List.of());

        LogSource source = LogSource.createFull("nginx", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/nginx/access.log", null, null, "prod", true,
                "beginning", null, null, List.of("nginx-rule"), null,
                null, "utf-8", "event_time", "UTC", List.of(), 1, null, null);
        LogSourceStore sources = mock(LogSourceStore.class);
        when(sources.revision()).thenReturn(1L);
        when(sources.get("collector-1")).thenReturn(Optional.empty());
        when(sources.get(source.id())).thenReturn(Optional.of(source));

        ParseRule rule = ParseRule.createWithId("nginx-rule", "Nginx access", source.id(),
                "REGEX", "user=(?<user>\\S+) src=(?<srcip>\\S+)", List.of(), List.of(), true, 1);
        ParseRuleStore rules = mock(ParseRuleStore.class);
        when(rules.revision()).thenReturn(1L);
        when(rules.get("nginx-rule")).thenReturn(rule);

        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                references, new ParserRegistry(), new IngestSourceResolver(sources),
                new ParsePipelineResolver(rules, new ParseRuleExecutor(new ParserRegistry())));

        String envelope = "{\"source_id\":\"" + source.id()
                + "\",\"collector_tag\":\"" + source.collectorTag()
                + "\",\"parse_format\":\"auto\",\"message\":\"user=ADMIN src=203.0.113.10\"}";
        var result = normalizer.normalize(envelope, "collector-1");

        assertEquals("ADMIN", result.event().fields().get("user"));
        assertEquals("203.0.113.10", result.event().fields().get("src_ip"));
        assertEquals(source.id(), result.event().fields().get("source_id"));
        assertEquals("nginx-rule", result.event().fields().get("parse_rule_id"));
        assertEquals("collector-1", result.collector());
    }

    @Test
    void preservesPipelineErrorsAndCountsFlatAndEcsFieldsForSparseDetection() {
        TenantContext.set("tenant-a");
        ParserRegistry parsers = mock(ParserRegistry.class);
        ReferenceSetStore references = mock(ReferenceSetStore.class);
        IngestSourceResolver sourceResolver = mock(IngestSourceResolver.class);
        ParsePipelineResolver pipeline = mock(ParsePipelineResolver.class);
        IngestSourceContext context = new IngestSourceContext(
                "collector-1", "source-1", ParseFormat.AUTO, List.of(), true);
        when(parsers.parse(anyString(), any(ParseFormat.class), isNull())).thenReturn(Map.of(
                CanonicalEvent.EVENT_MESSAGE, "raw",
                CanonicalEvent.EVENT_ACTION, "login",
                "custom_field", "value"));
        when(sourceResolver.resolve(anyString(), anyString())).thenReturn(context);
        when(pipeline.apply(any(IngestSourceContext.class), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ParsePipelineResolver.Result(
                        false, null, Map.of(), "invalid parse rule"));
        when(references.matchedSets(anyString())).thenReturn(List.of());

        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                references, parsers, sourceResolver, pipeline);

        var result = normalizer.normalize("raw", "collector-1");

        assertEquals("invalid parse rule", result.event().ecs().get("parse.error"));
        assertEquals("login", result.event().fields().get("action"));
        assertEquals("value", result.event().fields().get("custom_field"));
    }
}
