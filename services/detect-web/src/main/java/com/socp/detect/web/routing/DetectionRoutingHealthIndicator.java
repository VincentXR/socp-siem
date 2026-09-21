package com.socp.detect.web.routing;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness-visible routing compatibility; unsupported ACTIVE rules fail closed. */
@Component("detectionRouting")
public class DetectionRoutingHealthIndicator implements HealthIndicator {

    private final DetectionRoutingPlanRegistry plans;
    private final DetectionRoutingRuntime runtime;

    public DetectionRoutingHealthIndicator(DetectionRoutingPlanRegistry plans,
                                           DetectionRoutingRuntime runtime) {
        this.plans = plans;
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        DetectionRoutingPlan defaultPlan = plans.plan("default");
        java.util.List<String> deploymentErrors = runtime.validationErrors();
        boolean topologyCompatible = plans.topologyCompatible("default", defaultPlan);
        if (!deploymentErrors.isEmpty() || !defaultPlan.supported() || !topologyCompatible
                || plans.hasKnownUnsupportedPlan()) {
            return Health.down()
                    .withDetail("capabilityStatus", topologyCompatible
                            ? runtime.capabilityStatus(defaultPlan) : "TOPOLOGY_CONFLICT")
                    .withDetail("routingVersion", defaultPlan.routingVersion())
                    .withDetail("planVersion", defaultPlan.version())
                    .withDetail("deploymentErrors", deploymentErrors)
                    .withDetail("rulePlanErrors", defaultPlan.allErrors())
                    .withDetail("topologyCompatible", topologyCompatible)
                    .build();
        }
        return Health.up()
                .withDetail("capabilityStatus", runtime.capabilityStatus(defaultPlan))
                .withDetail("routingVersion", defaultPlan.routingVersion())
                .withDetail("planVersion", defaultPlan.version())
                .withDetail("maximumFanOut", defaultPlan.maximumFanOut())
                .build();
    }
}
