package com.socp.detect.web.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deployment contract for legacy, shadow and primary routed detection modes. */
@Component
public class DetectionRoutingRuntime {

    public enum Mode {
        LEGACY,
        SHADOW,
        PRIMARY
    }

    private final Mode mode;
    private final String sourceTopic;
    private final String deliveryTopic;
    private final String inputTopic;
    private final String outputMode;
    private final boolean publisherEnabled;
    private final String detectionGroupId;
    private final String routerGroupId;

    public DetectionRoutingRuntime(
            @Value("\${socp.detect.routing.mode:legacy}") String mode,
            @Value("\${socp.detect.routing.source-topic:\${socp.kafka.topic:socp-events}}") String sourceTopic,
            @Value("\${socp.detect.routing.delivery-topic:socp-detection-routed-v2}") String deliveryTopic,
            @Value("\${socp.detect.input-topic:\${socp.kafka.topic:socp-events}}") String inputTopic,
            @Value("\${socp.detect.output-mode:primary}") String outputMode,
            @Value("\${socp.detect.routing.publisher-enabled:false}") boolean publisherEnabled,
            @Value("\${socp.kafka.group-id:socp-detect}") String detectionGroupId,
            @Value("\${socp.detect.routing.source-group-id:socp-detect-router-v2}") String routerGroupId) {
        this.mode = parse(mode);
        this.sourceTopic = clean(sourceTopic, "socp-events");
        this.deliveryTopic = clean(deliveryTopic, "socp-detection-routed-v2");
        this.inputTopic = clean(inputTopic, "socp-events");
        this.outputMode = clean(outputMode, "primary").toLowerCase(Locale.ROOT);
        this.publisherEnabled = publisherEnabled;
        this.detectionGroupId = clean(detectionGroupId, "socp-detect");
        this.routerGroupId = clean(routerGroupId, "socp-detect-router-v2");
    }

    public Mode mode() {
        return mode;
    }

    public boolean routedDetection() {
        return mode == Mode.SHADOW || mode == Mode.PRIMARY;
    }

    public boolean formalOutput() {
        return mode != Mode.SHADOW;
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (sourceTopic.equals(deliveryTopic)) {
            errors.add("source topic and routed delivery topic must differ");
        }
        switch (mode) {
            case LEGACY -> {
                if (!inputTopic.equals(sourceTopic)) {
                    errors.add("legacy mode must consume the canonical source topic");
                }
                if (!"primary".equals(outputMode)) {
                    errors.add("legacy mode is the formal output path and requires output-mode=primary");
                }
            }
            case SHADOW -> {
                if (!inputTopic.equals(deliveryTopic)) {
                    errors.add("shadow mode must consume the routed delivery topic");
                }
                if (!"shadow".equals(outputMode)) {
                    errors.add("shadow mode requires output-mode=shadow");
                }
            }
            case PRIMARY -> {
                if (!inputTopic.equals(deliveryTopic)) {
                    errors.add("primary routed mode must consume the routed delivery topic");
                }
                if (!"primary".equals(outputMode)) {
                    errors.add("primary routed mode requires output-mode=primary");
                }
            }
        }
        if (publisherEnabled && sourceTopic.equals(inputTopic) && routedDetection()) {
            errors.add("routed detection cannot consume the canonical source topic");
        }
        if (publisherEnabled && routerGroupId.equals(detectionGroupId)
                && sourceTopic.equals(inputTopic)) {
            errors.add("router and detector must not share a consumer group on the same topic");
        }
        return List.copyOf(errors);
    }

    public String capabilityStatus(DetectionRoutingPlan plan) {
        if (!validationErrors().isEmpty()) return "INVALID_DEPLOYMENT";
        if (plan != null && !plan.supported()) return "UNSUPPORTED_RULE_PLAN";
        return switch (mode) {
            case LEGACY -> plan != null && plan.statefulDimensionCount() > 1
                    ? "LEGACY_PARTIAL" : "LEGACY_COMPATIBLE";
            case SHADOW -> "SHADOW_VALIDATION";
            case PRIMARY -> "ROUTED_V2_PRIMARY";
        };
    }

    public Map<String, Object> summary(DetectionRoutingPlan plan) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("capabilityStatus", capabilityStatus(plan));
        out.put("sourceTopic", sourceTopic);
        out.put("deliveryTopic", deliveryTopic);
        out.put("inputTopic", inputTopic);
        out.put("outputMode", outputMode);
        out.put("publisherEnabled", publisherEnabled);
        out.put("detectionGroupId", detectionGroupId);
        out.put("routerGroupId", routerGroupId);
        out.put("validationErrors", validationErrors());
        return Map.copyOf(out);
    }

    private static Mode parse(String value) {
        try {
            return Mode.valueOf(clean(value, "legacy").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("socp.detect.routing.mode must be legacy, shadow or primary", failure);
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
