package com.socp.search.config.service;

import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.LogSource;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs the live source normalizer without persistence, telemetry or downstream side effects. */
@Service
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class IngestPreviewService {
    private final IngestEventNormalizer normalizer;
    public IngestPreviewService(IngestEventNormalizer normalizer) { this.normalizer = normalizer; }

    public Map<String, Object> preview(LogSource source, String sample) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", source.id());
        result.put("sample", sample);
        result.put("writesEvent", false);
        result.put("mayTriggerDownstreamActions", false);
        result.put("parserVersion", normalizer.parserVersion(sample, source.id()));
        try {
            var normalized = normalizer.normalize(sample, source.id(), "preview", normalizer.lookupSnapshot());
            var event = normalized.event();
            result.put("ok", true);
            result.put("matched", true);
            result.put("fields", event.fields());
            result.put("ecs", event.ecs());
            result.put("event", event);
            result.put("ruleId", event.fields().getOrDefault("parse_rule_id", ""));
        } catch (IngestParseException invalid) {
            result.put("ok", false);
            result.put("matched", false);
            result.put("fields", Map.of());
            result.put("error", invalid.getMessage());
        }
        return result;
    }
}
