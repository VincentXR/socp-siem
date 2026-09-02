package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.persistence.store.ParseRuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects and caches the parser candidates for one configured log source.
 * Explicit source bindings win; otherwise only enabled global/source-scoped
 * rules are used as a sparse-event compatibility fallback.
 */
@Component
public class ParsePipelineResolver {

    private static final Logger log = LoggerFactory.getLogger(ParsePipelineResolver.class);
    private final ParseRuleStore rules;
    private final ParseRuleExecutor executor;
    private final Map<String, List<ParseRuleExecutor.CompiledRule>> cache = new ConcurrentHashMap<>();
    private volatile long cacheRevision = Long.MIN_VALUE;

    public ParsePipelineResolver(ParseRuleStore rules, ParseRuleExecutor executor) {
        this.rules = rules;
        this.executor = executor;
    }

    public Result apply(IngestSourceContext context, String original, String rawLog, boolean sparseBase) {
        if (context == null || !context.resolved()) return Result.notMatched();
        if (!context.hasExplicitRules() && !sparseBase) return Result.notMatched();

        String inputFallback = rawLog == null || rawLog.isBlank() ? original : rawLog;
        String lastError = null;
        for (ParseRuleExecutor.CompiledRule compiled : resolve(context)) {
            String input = switch (compiled.format()) {
                case "JSON" -> jsonInput(original, inputFallback);
                case "SYSLOG", "CEF", "LEEF" -> original;
                default -> inputFallback;
            };
            ParseRuleExecutor.Result result = executor.execute(compiled, input);
            if (result.matched()) {
                return new Result(true, compiled.rule().id(), result.fields(), null);
            }
            if (result.error() != null && !result.error().isBlank()) lastError = result.error();
        }
        return new Result(false, null, Map.of(), lastError);
    }

    private static String jsonInput(String original, String rawLog) {
        if (rawLog != null && rawLog.stripLeading().startsWith("{")) return rawLog;
        return original;
    }

    private List<ParseRuleExecutor.CompiledRule> resolve(IngestSourceContext context) {
        String tenant = TenantContext.require();
        long revision = rules.revision();
        if (cacheRevision != revision) {
            synchronized (cache) {
                if (cacheRevision != revision) {
                    cache.clear();
                    cacheRevision = revision;
                }
            }
        }
        String key = tenant + "|" + context.sourceId() + "|"
                + context.parseRuleIds() + "|" + revision;
        return cache.computeIfAbsent(key, ignored -> compileCandidates(context));
    }

    private List<ParseRuleExecutor.CompiledRule> compileCandidates(IngestSourceContext context) {
        List<ParseRule> candidates = new ArrayList<>();
        if (context.hasExplicitRules()) {
            for (String id : context.parseRuleIds()) {
                ParseRule rule = rules.get(id);
                if (rule == null || !rule.enabled() || !scopeMatches(rule, context.sourceId())) continue;
                candidates.add(rule);
            }
        } else {
            candidates.addAll(rules.enabled().stream()
                    .filter(rule -> scopeMatches(rule, context.sourceId()))
                    .sorted(java.util.Comparator.comparingInt(ParseRule::order))
                    .toList());
        }

        List<ParseRuleExecutor.CompiledRule> compiled = new ArrayList<>();
        for (ParseRule rule : candidates) {
            try {
                compiled.add(executor.compile(rule));
            } catch (RuntimeException invalid) {
                log.warn("Ignoring invalid parse rule id={} source={}: {}",
                        rule.id(), context.sourceId(), invalid.getMessage());
            }
        }
        return List.copyOf(compiled);
    }

    private static boolean scopeMatches(ParseRule rule, String sourceId) {
        return rule.sourceId() == null || rule.sourceId().isBlank()
                || (sourceId != null && sourceId.equals(rule.sourceId()));
    }

    public record Result(boolean matched, String ruleId, Map<String, String> fields, String error) {
        static Result notMatched() {
            return new Result(false, null, Map.of(), null);
        }
    }
}
