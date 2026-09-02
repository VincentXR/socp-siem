package com.socp.search.config.service;

import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Validates a configured parser rule against one sample log line. */
@Service
public class ParsePreviewService {

    private final ParseRuleStore store;
    private final ParseRuleExecutor executor;

    @Autowired
    public ParsePreviewService(ParseRuleStore store, ParseRuleExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    /** Source-compatible constructor for lightweight preview tests. */
    public ParsePreviewService(ParseRuleStore store) {
        this(store, new ParseRuleExecutor(new ParserRegistry()));
    }

    /**
     * Preview an existing rule, or a temporary rule when the caller supplies
     * format and pattern directly. Preview and live ingestion share the same
     * compiler/executor so filters and built-in formats cannot drift apart.
     */
    public Map<String, Object> preview(String ruleId, String format, String pattern, String line) {
        ParseRule rule = ruleId != null && !ruleId.isBlank() ? store.get(ruleId) : null;
        ParseRule effective = rule == null
                ? ParseRule.create("(temporary)", null,
                format == null || format.isBlank() ? "REGEX" : format,
                pattern, java.util.List.of(), java.util.List.of(), java.util.List.of(), true, 0)
                : rule;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule", rule == null ? "(temporary)" : rule.name());
        result.put("format", effective.format());
        result.put("matched", false);
        result.put("fields", Map.of());

        try {
            ParseRuleExecutor.Result parsed = executor.execute(effective, line);
            result.put("matched", parsed.matched());
            result.put("fields", previewFields(parsed.fields()));
            if (parsed.error() != null && !parsed.error().isBlank()) {
                result.put("error", parsed.error());
            }
        } catch (RuntimeException failure) {
            result.put("error", failure.getMessage());
        }
        return result;
    }

    /** Keep the existing preview response vocabulary while live fields stay canonical. */
    private static Map<String, String> previewFields(Map<String, String> canonical) {
        Map<String, String> fields = new LinkedHashMap<>();
        canonical.forEach((key, value) -> fields.put(previewKey(key), value));
        return fields;
    }

    private static String previewKey(String key) {
        return switch (key) {
            case "source.ip" -> "src_ip";
            case "source.port" -> "src_port";
            case "destination.ip" -> "dst_ip";
            case "destination.port" -> "dst_port";
            case "host.name" -> "host";
            case "user.name" -> "user";
            case "process.name" -> "process";
            case "process.pid" -> "pid";
            case "process.command_line" -> "cmdline";
            case "file.path" -> "file_path";
            case "file.hash.sha256" -> "sha256";
            case "network.protocol" -> "protocol";
            case "event.code" -> "code";
            case "event.category" -> "category";
            case "event.type" -> "type";
            case "event.action" -> "action";
            case "event.severity" -> "severity";
            case "event.message" -> "msg";
            default -> key;
        };
    }
}
