package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.parser.CanonicalEvent;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
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
}
