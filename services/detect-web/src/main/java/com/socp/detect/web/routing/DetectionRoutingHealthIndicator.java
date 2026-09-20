package com.socp.detect.web.routing;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness-visible routing compatibility; unsupported ACTIVE rules fail closed. */
@Component("detectionRouting")
public class DetectionRoutingHealthIndicator implements HealthIndicator {

    private final DetectionRoutingPlanRegistry plans;

    public DetectionRoutingHealthIndicator(DetectionRoutingPlanRegistry plans) {
        this.plans = plans;
    }

    @Override
    public Health health() {
        DetectionRoutingPlan defaultPlan = plans.plan("default");
        if (!defaultPlan.supported() || plans.hasKnownUnsupportedPlan()) {
            return Health.down()
                    .withDetail("routingVersion", defaultPlan.routingVersion())
                    .withDetail("planVersion", defaultPlan.version())
                    .withDetail("errors", defaultPlan.allErrors())
                    .build();
        }
        return Health.up()
                .withDetail("routingVersion", defaultPlan.routingVersion())
                .withDetail("planVersion", defaultPlan.version())
                .withDetail("maximumFanOut", defaultPlan.maximumFanOut())
                .build();
    }
}
