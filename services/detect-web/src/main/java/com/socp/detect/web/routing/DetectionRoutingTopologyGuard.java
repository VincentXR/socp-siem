package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.entity.DetectionRouteTopologyEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteTopologyRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.rule.partition.DetectionDelivery;
import com.socp.rule.util.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Serializes rule mutations with the first topology pin, across replicas. */
@Component
public class DetectionRoutingTopologyGuard {
    static final String UNBOUND = "unbound";
    private final DetectionRouteTopologyRepository topology;
    private final RuleRepository rules;
    private final int maxDimensions;
    private final TransactionTemplate transaction;
    private final TransactionTemplate initialization;

    public DetectionRoutingTopologyGuard(DetectionRouteTopologyRepository topology,
                                         RuleRepository rules,
                                         PlatformTransactionManager manager,
                                         @Value("${socp.detect.routing.max-stateful-dimensions:8}") int maxDimensions) {
        this.topology = topology;
        this.rules = rules;
        this.maxDimensions = maxDimensions;
        this.transaction = new TransactionTemplate(manager);
        this.initialization = new TransactionTemplate(manager);
        this.initialization.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T mutate(String tenant, Supplier<T> work) {
        ensureRow(tenant);
        return transaction.execute(status -> {
            topology.lockByTenantAndVersion(tenant, DetectionDelivery.ROUTING_VERSION).orElseThrow();
            return work.get();
        });
    }

    public void validateMutation(String tenant, String ruleId, Map<String, Object> replacement) {
        var pin = topology.findByTenantIdAndRoutingVersion(tenant, DetectionDelivery.ROUTING_VERSION);
        if (pin.isEmpty() || UNBOUND.equals(pin.get().getPlanVersion())) return;
        List<Map<String, Object>> prospective = new ArrayList<>();
        rules.findByTenantId(tenant).forEach(row -> {
            if (!ruleId.equals(row.getId())) prospective.add(Json.parseObject(row.getSpec()));
        });
        if (replacement != null) prospective.add(replacement);
        var plan = DetectionRoutingPlan.compile(prospective, maxDimensions);
        if (!plan.supported() || !plan.version().equals(pin.get().getPlanVersion())) {
            throw ApiException.of(409, "rule change conflicts with the pinned routing topology; "
                    + "migrate to a new routing version and delivery topic before changing dimensions or sources");
        }
    }

    public void pinIfNeeded(String tenant, Supplier<DetectionRoutingPlan> freshPlan) {
        var current = topology.findByTenantIdAndRoutingVersion(tenant, DetectionDelivery.ROUTING_VERSION);
        if (current.isPresent() && !UNBOUND.equals(current.get().getPlanVersion())) return;
        mutate(tenant, () -> {
            var row = topology.findByTenantIdAndRoutingVersion(tenant, DetectionDelivery.ROUTING_VERSION)
                    .orElseThrow();
            if (UNBOUND.equals(row.getPlanVersion())) {
                DetectionRoutingPlan plan = freshPlan.get();
                if (!plan.supported()) {
                    throw new DetectionRoutingPlan.UnsupportedRoutingPlanException(String.join("; ", plan.allErrors()));
                }
                topology.saveAndFlush(new DetectionRouteTopologyEntity(tenant,
                        plan.routingVersion(), plan.version(), row.getCreatedAt()));
            }
            return null;
        });
    }

    public boolean compatible(String tenant, DetectionRoutingPlan plan) {
        return topology.findByTenantIdAndRoutingVersion(tenant, plan.routingVersion())
                .map(row -> UNBOUND.equals(row.getPlanVersion()) || plan.version().equals(row.getPlanVersion()))
                .orElse(true);
    }

    private void ensureRow(String tenant) {
        if (topology.findByTenantIdAndRoutingVersion(tenant, DetectionDelivery.ROUTING_VERSION).isPresent()) return;
        try {
            initialization.executeWithoutResult(status -> {
                if (topology.findByTenantIdAndRoutingVersion(tenant, DetectionDelivery.ROUTING_VERSION).isEmpty()) {
                    var row = new DetectionRouteTopologyEntity(tenant,
                            DetectionDelivery.ROUTING_VERSION, UNBOUND, Instant.now());
                    topology.insertUnbound(row.getId(), tenant, row.getRoutingVersion(), UNBOUND, row.getCreatedAt());
                }
            });
        } catch (DataIntegrityViolationException race) {
            if (topology.findByTenantIdAndRoutingVersion(tenant, DetectionDelivery.ROUTING_VERSION).isEmpty()) throw race;
        }
    }
}
