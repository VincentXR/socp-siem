package com.socp.detect.web.routing;

import com.socp.rule.config.RuleSpec;
import com.socp.rule.partition.DetectionDelivery;
import com.socp.rule.partition.RoutingDimension;
import com.socp.rule.state.StatefulRule;
import com.socp.rule.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Executable, bounded routing plan derived from the tenant's ACTIVE rules. */
public final class DetectionRoutingPlan {

    private static final Set<String> STATEFUL_TYPES =
            Set.of("threshold", "correlation", "correlation-set", "baseline", "rare");

    private final String version;
    private final int maxStatefulDimensions;
    private final Map<String, Set<String>> sourcesByDimension;
    private final List<RuleCompatibility> rules;
    private final List<String> errors;

    private DetectionRoutingPlan(String version, int maxStatefulDimensions,
                                 Map<String, Set<String>> sourcesByDimension,
                                 List<RuleCompatibility> rules, List<String> errors) {
        this.version = version;
        this.maxStatefulDimensions = maxStatefulDimensions;
        this.sourcesByDimension = Map.copyOf(sourcesByDimension);
        this.rules = List.copyOf(rules);
        this.errors = List.copyOf(errors);
    }

    public static DetectionRoutingPlan compile(List<Map<String, Object>> documents,
                                               int maxStatefulDimensions) {
        int limit = Math.max(1, Math.min(32, maxStatefulDimensions));
        Map<String, Set<String>> dimensions = new TreeMap<>();
        List<RuleCompatibility> statuses = new ArrayList<>();
        List<String> planErrors = new ArrayList<>();

        if (documents != null) {
            for (Map<String, Object> document : documents) {
                if (document == null || !RuleSpec.isLive(document)) continue;
                String type = text(document.get("type"));
                if (type == null || !STATEFUL_TYPES.contains(type.toLowerCase(Locale.ROOT))) continue;
                String ruleId = text(document.get("id"));
                String grouping = first(text(document.get("groupBy")), text(document.get("keyField")));
                String routing = first(text(document.get("routingField")), grouping);
                List<String> sources = strings(document.get("dataSources"));
                List<String> reasons = new ArrayList<>();
                if (grouping == null) reasons.add("stateful ACTIVE rule has no groupBy/keyField");
                if (grouping != null && routing != null && !grouping.equals(routing)) {
                    reasons.add("routingField must equal groupBy for routed state");
                }
                if (grouping != null) reasons.addAll(RoutingDimension.validationErrors(grouping));
                // Executable compatibility, not just field vocabulary: a stateful
                // ACTIVE rule that cannot compile is silently isolated by
                // DetectionEngineFactory at build time and stops detecting with
                // every health probe green. The plan must surface that instead of
                // the deployment claiming routed cross-dimension coverage.
                if (reasons.isEmpty()) {
                    try {
                        RuleSpec spec = new RuleSpec(document);
                        if (!(spec.toRule() instanceof StatefulRule)) {
                            reasons.add("declared type does not compile to a stateful rule");
                        }
                    } catch (RuntimeException compileFailure) {
                        reasons.add("stateful rule does not compile: "
                                + (compileFailure.getMessage() == null
                                        ? compileFailure.getClass().getSimpleName()
                                        : compileFailure.getMessage()));
                    }
                }

                boolean supported = reasons.isEmpty();
                if (supported) {
                    Set<String> sourceSet = dimensions.computeIfAbsent(grouping,
                            ignored -> new LinkedHashSet<>());
                    if (sources.isEmpty()) sourceSet.add("*");
                    else sources.stream().map(value -> value.toLowerCase(Locale.ROOT)).forEach(sourceSet::add);
                }
                statuses.add(new RuleCompatibility(
                        ruleId == null ? "<missing-id>" : ruleId,
                        type == null ? "<missing-type>" : type,
                        grouping, sources, supported ? "SUPPORTED" : "UNSUPPORTED",
                        supported ? "routed by shared dimension copy"
                                : String.join("; ", reasons)));

            }
        }

        if (dimensions.size() > limit) {
            planErrors.add("ACTIVE stateful rule set requires " + dimensions.size()
                    + " routing dimensions but deployment max is " + limit);
        }
        String version = fingerprint(DetectionDelivery.ROUTING_VERSION, dimensions);
        return new DetectionRoutingPlan(version, limit, dimensions, statuses, planErrors);
    }

    public boolean supported() {
        return errors.isEmpty() && rules.stream().noneMatch(rule -> "UNSUPPORTED".equals(rule.status()));
    }

    public String version() {
        return version;
    }

    public String routingVersion() {
        return DetectionDelivery.ROUTING_VERSION;
    }

    public int maxStatefulDimensions() {
        return maxStatefulDimensions;
    }

    public int statefulDimensionCount() {
        return sourcesByDimension.size();
    }

    public int maximumFanOut() {
        return 1 + sourcesByDimension.size();
    }

    public List<String> dimensionsForSource(String source) {
        if (!supported()) {
            throw new UnsupportedRoutingPlanException(String.join("; ", allErrors()));
        }
        String normalized = source == null ? "unknown" : source.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        sourcesByDimension.forEach((dimension, sources) -> {
            if (sources.contains("*") || sources.contains(normalized)) out.add(dimension);
        });
        return List.copyOf(out);
    }

    public List<RuleCompatibility> rules() {
        return rules;
    }

    public List<String> allErrors() {
        List<String> out = new ArrayList<>(errors);
        rules.stream().filter(rule -> "UNSUPPORTED".equals(rule.status()))
                .map(rule -> rule.ruleId() + ": " + rule.reason()).forEach(out::add);
        return List.copyOf(out);
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", DetectionDelivery.SCHEMA_VERSION);
        out.put("routingVersion", DetectionDelivery.ROUTING_VERSION);
        out.put("planVersion", version);
        out.put("status", supported() ? "SUPPORTED" : "UNSUPPORTED");
        out.put("maxStatefulDimensions", maxStatefulDimensions);
        out.put("statefulDimensions", sourcesByDimension);
        out.put("maximumFanOut", maximumFanOut());
        out.put("fieldAliases", RoutingDimension.aliases());
        out.put("rules", rules);
        out.put("errors", allErrors());
        return Map.copyOf(out);
    }

    /**
     * Fingerprint only the routing topology. Matcher/threshold/message changes
     * do not require repartitioning; source->dimension or alias/encoding changes
     * do. A topology change is therefore an explicit migration boundary rather
     * than an incidental consequence of normal rule hot reload.
     */
    private static String fingerprint(String routingVersion,
                                      Map<String, Set<String>> dimensions) {
        try {
            Map<String, Object> topology = new TreeMap<>();
            dimensions.forEach((dimension, sources) -> topology.put(
                    dimension, sources.stream().sorted().toList()));
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("schemaVersion", DetectionDelivery.SCHEMA_VERSION);
            canonical.put("routingVersion", routingVersion);
            canonical.put("fieldAliases", RoutingDimension.aliases());
            canonical.put("sourceDimensions", topology);
            byte[] bytes = Json.mapper().writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(canonical);
            return "route-plan-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 24);
        } catch (Exception failure) {
            String fallback = routingVersion + "|" + RoutingDimension.aliases() + "|" + dimensions;
            return "route-plan-" + Integer.toHexString(
                    java.util.Arrays.hashCode(fallback.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(java.util.Objects::nonNull)
                .map(String::valueOf).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private static String first(String first, String second) {
        return first == null ? second : first;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    public record RuleCompatibility(String ruleId, String type, String dimension,
                                    List<String> dataSources, String status, String reason) {
        public RuleCompatibility {
            dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        }
    }

    public static final class UnsupportedRoutingPlanException extends IllegalStateException {
        public UnsupportedRoutingPlanException(String message) {
            super(message == null || message.isBlank() ? "routing plan is unsupported" : message);
        }
    }
}
