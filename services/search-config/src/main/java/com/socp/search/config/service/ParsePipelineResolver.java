package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.ConfigCacheProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.persistence.store.ParseRuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects and caches the parser candidates for one configured log source.
 * Explicit source bindings win; otherwise only enabled global/source-scoped
 * rules are used as a sparse-event compatibility fallback.
 *
 * <p>Cache invalidation is per tenant (a write by another tenant must not flush this
 * tenant's compiled pipelines) and TTL-bounded, so a rule saved through another SEARCH
 * replica becomes visible here within one configuration-cache window instead of staying
 * stale until a local write or a restart.
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class ParsePipelineResolver {

    private static final Logger log = LoggerFactory.getLogger(ParsePipelineResolver.class);
    private final ParseRuleStore rules;
    private final ParseRuleExecutor executor;
    private final Map<String, List<ParseRuleExecutor.CompiledRule>> cache = new ConcurrentHashMap<>();
    private final TenantCacheGuard guard;

    public ParsePipelineResolver(ParseRuleStore rules, ParseRuleExecutor executor) {
        this(rules, executor, new ConfigCacheProperties());
    }

    @Autowired
    public ParsePipelineResolver(ParseRuleStore rules, ParseRuleExecutor executor,
                                 ConfigCacheProperties properties) {
        this.rules = rules;
        this.executor = executor;
        this.guard = new TenantCacheGuard(properties.getTtlMs());
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
        long revision = rules.revision(tenant);
        if (guard.isStale(tenant, revision)) {
            synchronized (cache) {
                cache.keySet().removeIf(key -> key.startsWith(tenant + "|"));
                guard.markFresh(tenant, revision);
            }
        }
        String key = tenant + "|" + context.sourceId() + "|" + context.parseRuleIds();
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
