package com.socp.detect.web.service;

import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.rules.Rule;
import com.socp.rule.state.StatefulRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds tenant/shard rule engines and owns per-engine rule-isolation accounting. */
final class DetectionEngineFactory {

    private static final Logger LOG = LoggerFactory.getLogger(DetectionEngineFactory.class);

    private final RuleSpecStore store;
    private final RecentAlertSink sink;
    private final Suppressor suppressor;
    private final RuleProcessingObserver processingObserver;
    private final Map<String, Integer> isolatedRules = new ConcurrentHashMap<>();

    DetectionEngineFactory(RuleSpecStore store, RecentAlertSink sink, Suppressor suppressor,
                           RuleProcessingObserver processingObserver) {
        this.store = store;
        this.sink = sink;
        this.suppressor = suppressor;
        this.processingObserver = processingObserver;
    }

    RuleEngine build(String engineKey, String tenant, List<SecurityEvent> history,
                     Runnable durableCommitGuard) {
        List<Map<String, Object>> documents = new java.util.ArrayList<>();
        for (Map<String, Object> document : store.list(tenant)) {
            if (RuleSpec.isLive(document)) documents.add(document);
        }

        List<Rule> rules = new java.util.ArrayList<>(documents.size());
        Map<String, String> stateCompatibilityVersions = new LinkedHashMap<>();
        Map<String, RuleEngine.RoutingDimension> routingDimensions = new LinkedHashMap<>();
        int isolated = 0;
        for (Map<String, Object> document : documents) {
            String documentId = String.valueOf(document.get("id"));
            try {
                RuleSpec spec = new RuleSpec(document);
                Rule rule = spec.toRule();
                rules.add(rule);
                if (rule instanceof StatefulRule stateful) {
                    stateCompatibilityVersions.put(spec.id,
                            stateful.stateVersion() + ":" + spec.stateSemanticsFingerprint());
                    if (spec.groupBy != null && !spec.groupBy.isBlank()) {
                        routingDimensions.put(spec.id, new RuleEngine.RoutingDimension(
                                spec.groupBy, Math.max(1L, spec.window.getSeconds())));
                    }
                } else {
                    stateCompatibilityVersions.put(spec.id,
                            "stateless-v1:" + spec.stateSemanticsFingerprint());
                }
            } catch (RuntimeException ruleFailure) {
                isolated++;
                LOG.warn("Skipping undetectable rule tenant={} rule={}: {}",
                        tenant, documentId, describe(ruleFailure), ruleFailure);
            }
        }

        if (isolated > 0) isolatedRules.put(engineKey, isolated);
        else isolatedRules.remove(engineKey);

        RuleEngine engine = new RuleEngine(
                rules, List.of(sink), suppressor, processingObserver,
                event -> {
                    var tenantScope = com.socp.platform.tenant.context.TenantContext.open(
                            event.requireTenantId());
                    return tenantScope::close;
                }, durableCommitGuard, stateCompatibilityVersions, routingDimensions);
        try {
            engine.restore(history);
        } catch (RuntimeException failure) {
            engine.close();
            isolatedRules.remove(engineKey);
            throw failure;
        }
        return engine;
    }

    int isolatedCount(String keyPrefix) {
        return isolatedRules.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(keyPrefix))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    void removeIsolation(String engineKey) {
        isolatedRules.remove(engineKey);
    }

    void clearIsolation() {
        isolatedRules.clear();
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown engine build failure";
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
