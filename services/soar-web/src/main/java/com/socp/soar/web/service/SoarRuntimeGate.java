package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.SoarRuntimeProperties;
import org.springframework.http.HttpStatus;
import static com.socp.soar.web.service.SoarService.error;

/** Deployment rollout gates checked at command admission. */
final class SoarRuntimeGate {
    private SoarRuntimeProperties runtimeProperties;
    void setRuntimeProperties(SoarRuntimeProperties properties) { this.runtimeProperties = properties; }

    void requireControlPlane() {
        if (runtimeProperties != null && !runtimeProperties.isControlPlaneEnabled()) {
            throw error(HttpStatus.GONE, "SOAR_CONTROL_PLANE_DISABLED",
                    "SOAR control plane is disabled for this deployment");
        }
    }

    void requireEvaluation() {
        requireExecution(TenantContext.require());
        if (runtimeProperties != null && !runtimeProperties.isEvaluationEnabled()) {
            throw error(HttpStatus.GONE, "SOAR_EVALUATION_DISABLED",
                    "SOAR event evaluation is disabled for this deployment");
        }
    }

    void requireExecution(String tenant) {
        requireControlPlane();
        if (runtimeProperties == null) return;
        if (!runtimeProperties.isExecutionEnabled()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_EXECUTION_DISABLED",
                    "SOAR execution is paused by the deployment feature flag");
        }
        String configured = runtimeProperties.getExecutionTenantAllowlist();
        if (configured == null || configured.isBlank()) return;
        boolean allowed = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .anyMatch(value -> value.equals(tenant));
        if (!allowed) {
            throw error(HttpStatus.FORBIDDEN, "SOAR_TENANT_NOT_ENABLED",
                    "SOAR execution is not enabled for this tenant");
        }
    }
}
