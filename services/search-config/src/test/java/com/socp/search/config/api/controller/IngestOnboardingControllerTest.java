package com.socp.search.config.api.controller;

import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.api.request.SinkTargetRequest;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.search.config.service.IngestPreviewService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IngestOnboardingControllerTest {
    @Test void setupDistinguishesSavedDraftFromApplicationAndDescribesInvalidBindings() {
        var sources = mock(LogSourceStore.class);
        var outputs = mock(SinkTargetStore.class);
        var rules = mock(ParseRuleStore.class);
        var source = LogSource.createFull("draft", SourceType.FILE, ParseFormat.AUTO, null, null, null, null, false,
                null, null, null, List.of("missing"), null, null, null, null, null, List.of(), null, null, null, null);
        when(sources.get(source.id())).thenReturn(Optional.of(source));
        var previews = mock(IngestPreviewService.class);
        var controller = new IngestOnboardingController(sources, outputs, rules, previews);
        Map<String, Object> result = controller.setup(source.id()).data();
        assertEquals("UNKNOWN", result.get("appliedState"));
        assertEquals(true, result.get("nativeVector"));
        assertEquals(64, result.get("configurationVersion").toString().length());
        assertTrue(result.get("problems").toString().contains("disabled draft"));
        assertTrue(result.get("pipeline").toString().contains("exists=false"));
        assertEquals(result.get("configurationVersion"), controller.setup(source.id()).data().get("configurationVersion"));
        verifyNoInteractions(previews);
    }

    @Test void configurationFingerprintIgnoresReplicaLocalPlatformTimestampAndIncludesFallbackRules() {
        var sources = mock(LogSourceStore.class);
        var outputs = mock(SinkTargetStore.class);
        var rules = mock(ParseRuleStore.class);
        var source = LogSource.create("one", SourceType.FILE, ParseFormat.AUTO, "/logs/app", null, null, null, true);
        when(sources.get(source.id())).thenReturn(Optional.of(source));
        var first = new SinkTarget(SinkTargetStore.PLATFORM_INGEST_ID, "Platform", "SEARCH",
                "https://logs.example", null, true, java.time.Instant.EPOCH);
        var restarted = new SinkTarget(first.id(), first.name(), first.type(), first.uri(), null, true, java.time.Instant.now());
        when(outputs.resolveForRendering(null)).thenReturn(first, restarted, restarted);
        var rule = ParseRule.createWithId("fallback", "Fallback", null, "KV", null, List.of(), List.of(), true, 1);
        when(rules.enabled()).thenReturn(List.of(rule));
        when(rules.get(rule.id())).thenReturn(rule);
        var controller = new IngestOnboardingController(sources, outputs, rules, mock(IngestPreviewService.class));
        var initial = controller.setup(source.id()).data();
        var afterRestart = controller.setup(source.id()).data();
        assertEquals(initial.get("configurationVersion"), afterRestart.get("configurationVersion"));
        assertEquals("BUILTIN_THEN_SPARSE_FALLBACK", initial.get("pipelineMode"));
        assertTrue(initial.get("pipeline").toString().contains("fallback"));
        when(rules.get(rule.id())).thenReturn(ParseRule.createWithId("fallback", "Changed", null, "JSON", null, List.of(), List.of(), true, 1));
        assertNotEquals(initial.get("configurationVersion"), controller.setup(source.id()).data().get("configurationVersion"));
    }

    @Test void missingSourceCannotBePreviewed() {
        var sources = mock(LogSourceStore.class);
        var previews = mock(IngestPreviewService.class);
        when(sources.get("missing")).thenReturn(Optional.empty());
        var controller = new IngestOnboardingController(sources, mock(SinkTargetStore.class), mock(ParseRuleStore.class), previews);
        assertThrows(ApiException.class, () -> controller.preview("missing", new IngestOnboardingController.Sample("raw")));
        verifyNoInteractions(previews);
    }

    @Test void unsupportedOutputsAndCredentialBearingUrisAreRejectedWithoutNetworkProbe() {
        for (var request : List.of(
                new SinkTargetRequest("Kafka", "KAFKA", "kafka://broker:9092", null, true),
                new SinkTargetRequest("OpenSearch", "OPENSEARCH", "https://search.example", null, true),
                new SinkTargetRequest("Embedded credentials", "HTTP", "https://user:secret@logs.example", null, true),
                new SinkTargetRequest("Fragment", "HTTP", "https://logs.example/#secret", null, true))) {
            assertThrows(ApiException.class, request::validateHttpOutput);
        }
        var controller = new SinkTargetController(mock(SinkTargetStore.class));
        var result = controller.validate(new SinkTargetRequest("HTTP", "HTTP", "https://logs.example/ingest", null, true)).data();
        assertEquals(Map.of("valid", true, "networkTested", false, "writesEvent", false), result);
    }
}
