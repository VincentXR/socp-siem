package com.socp.detect.web.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.detect.web.persistence.entity.DetectionRouteSourceEntity;
import com.socp.detect.web.persistence.entity.DetectionRouteTopologyEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import com.socp.detect.web.persistence.repository.DetectionRouteSourceRepository;
import com.socp.detect.web.persistence.repository.DetectionRouteTopologyRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.partition.DetectionDelivery;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.partition.RoutingDimension;
import com.socp.rule.util.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Transactionally materializes all route copies for one canonical source event.
 * The source Kafka offset may advance only after this transaction commits.
 */
@Service
public class DetectionRouteOutboxService {

    private final DetectionRouteOutboxRepository repository;
    private final DetectionRoutingPlanRegistry plans;
    private final DetectionRouteSourceRepository sourceRepository;
    private final DetectionRouteTopologyRepository topologyRepository;
    private final String deliveryTopic;
    private final TransactionTemplate transactions;

    /** Compatibility constructor used by focused tests. */
    public DetectionRouteOutboxService(
            DetectionRouteOutboxRepository repository,
            DetectionRoutingPlanRegistry plans,
            String deliveryTopic) {
        this(repository, plans, null, null, deliveryTopic, null);
    }

    /** Focused constructor that also exercises persistent topology pinning. */
    DetectionRouteOutboxService(
            DetectionRouteOutboxRepository repository,
            DetectionRoutingPlanRegistry plans,
            DetectionRouteTopologyRepository topologyRepository,
            String deliveryTopic) {
        this(repository, plans, null, topologyRepository, deliveryTopic, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DetectionRouteOutboxService(
            DetectionRouteOutboxRepository repository,
            DetectionRoutingPlanRegistry plans,
            DetectionRouteSourceRepository sourceRepository,
            DetectionRouteTopologyRepository topologyRepository,
            @Value("${socp.detect.routing.delivery-topic:socp-detection-routed-v2}") String deliveryTopic,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.plans = plans;
        this.sourceRepository = sourceRepository;
        this.topologyRepository = topologyRepository;
        this.deliveryTopic = deliveryTopic == null || deliveryTopic.isBlank()
                ? "socp-detection-routed-v2" : deliveryTopic.trim();
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }


    public RouteResult route(String sourceTopic, int sourcePartition, long sourceOffset,
                             String raw) {
        if (sourceTopic == null || sourceTopic.isBlank()) {
            throw new IllegalArgumentException("source topic is required");
        }
        if (sourcePartition < 0 || sourceOffset < 0) {
            throw new IllegalArgumentException("source position must be non-negative");
        }
        try {
            // Parse tenant before opening the transaction. PostgreSQL RLS/session
            // context must be installed before the transaction obtains a
            // connection, not midway through a @Transactional method.
            ObjectNode canonical = parseObject(raw);
            RouteContext context = context(canonical, sourceTopic, sourcePartition, sourceOffset);
            return TenantContext.callWith(context.tenant(),
                    () -> inTransaction(() -> materialize(canonical, context)));
        } catch (DetectionRoutingPlan.UnsupportedRoutingPlanException unsupported) {
            throw unsupported;
        } catch (DataIntegrityViolationException raced) {
            // The fan-out transaction rolls back on a race. Redelivery observes
            // deterministic delivery ids and cannot expose a partial plan.
            throw raced;
        } catch (MalformedCanonicalEventException malformed) {
            // Only deterministic source-contract failures are terminal.
            return TenantContext.callWith("default",
                    () -> inTransaction(() -> recordTerminalFailure(
                            sourceTopic, sourcePartition, sourceOffset, raw, malformed)));
        }
    }

    private RouteResult materialize(ObjectNode canonical, RouteContext context) {
        if (sourceRepository != null) {
            var receipt = sourceRepository
                    .findByTenantIdAndSourceTopicAndSourcePartitionAndSourceOffset(
                            context.tenant(), context.sourceTopic(),
                            context.sourcePartition(), context.sourceOffset());
            if (receipt.isPresent()) return receiptResult(receipt.get());
        }

        // Backward-compatible recovery for rows written by an early v2 build
        // before source receipts existed.
        List<DetectionRouteOutboxEntity> frozen =
                repository.findBySourceTopicAndSourcePartitionAndSourceOffsetOrderByDeliveryIdAsc(
                        context.sourceTopic(), context.sourcePartition(), context.sourceOffset());
        if (frozen != null && !frozen.isEmpty()) {
            DetectionRouteOutboxEntity first = frozen.getFirst();
            RouteResult result = new RouteResult(first.getTenantId(), first.getSourceEventId(),
                    first.getPlanVersion(), frozen.size(), List.of(), false);
            persistSourceReceipt(context, first.getRoutingVersion(), first.getPlanVersion(),
                    frozen.size(), List.of(), "ROUTED", null);
            return result;
        }

        // A producer retry can place the same immutable business event at a
        // different Kafka offset. The source receipt is the long-lived
        // idempotency boundary even after published delivery rows are cleaned.
        if (sourceRepository != null) {
            var prior = sourceRepository
                    .findFirstByTenantIdAndSourceEventIdAndStatusInOrderByCreatedAtAsc(
                            context.tenant(), context.sourceEventId(),
                            List.of("ROUTED", "DUPLICATE"));
            if (prior.isPresent()) {
                List<String> missing = missingDimensions(prior.get().getMissingDimensions());
                persistSourceReceipt(context, prior.get().getRoutingVersion(),
                        prior.get().getPlanVersion(), prior.get().getDeliveryCount(),
                        missing, "DUPLICATE", null);
                return new RouteResult(context.tenant(), context.sourceEventId(),
                        prior.get().getPlanVersion(), prior.get().getDeliveryCount(),
                        missing, false);
            }
        }

        // Early v2 rows may predate source receipts; preserve their delivery
        // identity instead of generating a second fan-out at another offset.
        List<DetectionRouteOutboxEntity> duplicateDeliveries =
                repository.findByTenantIdAndSourceEventIdOrderByDeliveryIdAsc(
                        context.tenant(), context.sourceEventId());
        if (duplicateDeliveries != null && !duplicateDeliveries.isEmpty()) {
            DetectionRouteOutboxEntity first = duplicateDeliveries.getFirst();
            persistSourceReceipt(context, first.getRoutingVersion(), first.getPlanVersion(),
                    duplicateDeliveries.size(), List.of(), "DUPLICATE", null);
            return new RouteResult(context.tenant(), context.sourceEventId(),
                    first.getPlanVersion(), duplicateDeliveries.size(), List.of(), false);
        }

        DetectionRoutingPlan plan = plans.plan(context.tenant());
        if (!plan.supported()) {
            throw new DetectionRoutingPlan.UnsupportedRoutingPlanException(
                    String.join("; ", plan.allErrors()));
        }
        pinTopology(context.tenant(), plan);

        List<String> dimensions = plan.dimensionsForSource(context.event().source());
        List<String> missing = new ArrayList<>();
        List<ResolvedRoute> stateful = new ArrayList<>();
        for (String dimension : dimensions) {
            String value = RoutingDimension.value(context.event(), dimension);
            if (value == null || value.isBlank()) {
                missing.add(dimension);
                continue;
            }
            stateful.add(new ResolvedRoute(DetectionDelivery.Kind.STATEFUL, dimension, value));
        }

        List<ResolvedRoute> routes = new ArrayList<>(1 + stateful.size());
        routes.add(new ResolvedRoute(DetectionDelivery.Kind.STATELESS,
                DetectionDelivery.STATELESS_DIMENSION, context.sourceEventId()));
        routes.addAll(stateful);
        if (routes.size() > plan.maximumFanOut()) {
            throw new IllegalStateException("routing fan-out exceeded compiled plan bound");
        }

        Instant now = Instant.now();
        List<DetectionRouteOutboxEntity> rows = new ArrayList<>(routes.size());
        for (ResolvedRoute route : routes) {
            String deliveryId = DetectionDelivery.deliveryId(
                    context.tenant(), context.sourceEventId(), plan.routingVersion(),
                    route.kind(), route.dimension(), route.value());
            String routingKey = DetectionRoutingKey.forTuple(
                    context.tenant(), route.dimension(), route.value());
            String payload = routedPayload(canonical, context, plan, route,
                    deliveryId, missing);
            rows.add(new DetectionRouteOutboxEntity(
                    deliveryId, context.tenant(), context.sourceEventId(),
                    plan.routingVersion(), plan.version(), route.kind().name(),
                    route.dimension(), auditRouteValue(route.value()), routingKey,
                    context.sourceTopic(), context.sourcePartition(), context.sourceOffset(),
                    deliveryTopic, payload, now));
        }
        // One transaction owns the complete fan-out. A concurrent duplicate
        // may lose on the deterministic delivery ids; source redelivery then
        // observes the committed frozen rows above instead of exposing a
        // partially recomputed plan.
        repository.saveAllAndFlush(rows);
        persistSourceReceipt(context, plan.routingVersion(), plan.version(),
                routes.size(), missing, "ROUTED", null);
        return new RouteResult(context.tenant(), context.sourceEventId(),
                plan.version(), routes.size(), List.copyOf(missing), false);
    }

    private void persistSourceReceipt(RouteContext context, String routingVersion,
                                      String planVersion, int deliveryCount,
                                      List<String> missingDimensions, String status,
                                      String reason) {
        if (sourceRepository == null) return;
        sourceRepository.saveAndFlush(new DetectionRouteSourceEntity(
                context.tenant(), context.sourceTopic(), context.sourcePartition(),
                context.sourceOffset(), context.sourceEventId(), routingVersion,
                planVersion, deliveryCount,
                missingDimensions == null || missingDimensions.isEmpty()
                        ? null : String.join(",", missingDimensions),
                status, reason == null ? null : truncate(reason), Instant.now()));
    }

    private RouteResult receiptResult(DetectionRouteSourceEntity receipt) {
        List<String> missing = missingDimensions(receipt.getMissingDimensions());
        return new RouteResult(receipt.getTenantId(), receipt.getSourceEventId(),
                receipt.getPlanVersion(), receipt.getDeliveryCount(), missing,
                "DEAD".equalsIgnoreCase(receipt.getStatus()));
    }

    private static List<String> missingDimensions(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private void pinTopology(String tenant, DetectionRoutingPlan plan) {
        if (topologyRepository == null) return;
        var pinned = topologyRepository.findByTenantIdAndRoutingVersion(
                tenant, plan.routingVersion());
        if (pinned.isPresent()) {
            if (!plan.version().equals(pinned.get().getPlanVersion())) {
                throw new DetectionRoutingPlan.UnsupportedRoutingPlanException(
                        "routing topology changed for tenant " + tenant
                                + " routingVersion=" + plan.routingVersion()
                                + " pinnedPlan=" + pinned.get().getPlanVersion()
                                + " currentPlan=" + plan.version()
                                + "; use a new routing version AND a new routed delivery topic, "
                                + "then shadow/prewarm/cutover");
            }
            return;
        }
        topologyRepository.saveAndFlush(new DetectionRouteTopologyEntity(
                tenant, plan.routingVersion(), plan.version(), Instant.now()));
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactions == null) return work.get();
        return transactions.execute(status -> work.get());
    }

    private RouteResult recordTerminalFailure(String sourceTopic, int sourcePartition,
                                              long sourceOffset, String raw,
                                              RuntimeException failure) {
        String sourceEventId = positionEventId(sourceTopic, sourcePartition, sourceOffset);
        String id = DetectionDelivery.deliveryId("default", sourceEventId,
                DetectionDelivery.ROUTING_VERSION, DetectionDelivery.Kind.STATELESS,
                "_route_error", sourceEventId);
        if (sourceRepository != null) {
            var receipt = sourceRepository
                    .findByTenantIdAndSourceTopicAndSourcePartitionAndSourceOffset(
                            "default", sourceTopic, sourcePartition, sourceOffset);
            if (receipt.isPresent()) return receiptResult(receipt.get());
        }
        if (repository.findByTenantIdAndDeliveryId("default", id).isEmpty()) {
            Instant now = Instant.now();
            DetectionRouteOutboxEntity row = new DetectionRouteOutboxEntity(
                    id, "default", sourceEventId, DetectionDelivery.ROUTING_VERSION,
                    "unresolved", "ERROR", "_route_error", sourceEventId,
                    DetectionRoutingKey.forTuple("default", "_route_error", sourceEventId),
                    sourceTopic, sourcePartition, sourceOffset, deliveryTopic,
                    raw == null ? "" : raw, now);
            row.setStatus("DEAD");
            row.setLastError(truncate(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            repository.saveAndFlush(row);
        }
        if (sourceRepository != null) {
            sourceRepository.saveAndFlush(new DetectionRouteSourceEntity(
                    "default", sourceTopic, sourcePartition, sourceOffset,
                    sourceEventId, DetectionDelivery.ROUTING_VERSION,
                    "unresolved", 0, null, "DEAD",
                    truncate(failure.getClass().getSimpleName() + ": " + failure.getMessage()),
                    Instant.now()));
        }
        return new RouteResult("default", sourceEventId, "unresolved", 0,
                List.of(), true);
    }

    private static RouteContext context(ObjectNode canonical, String sourceTopic,
                                        int sourcePartition, long sourceOffset) {
        ObjectNode fieldsNode;
        JsonNode rawFields = canonical.get("fields");
        if (rawFields == null || rawFields.isNull()) {
            fieldsNode = canonical.putObject("fields");
        } else if (rawFields.isObject()) {
            fieldsNode = (ObjectNode) rawFields;
        } else {
            throw malformed("fields must be an object");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fieldsNode.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
        JsonNode ecs = canonical.get("ecs");
        if (ecs != null && !ecs.isNull()) {
            if (!ecs.isObject()) throw malformed("ecs must be an object");
            ecs.fields().forEachRemaining(entry -> fields.putIfAbsent(entry.getKey(), entry.getValue().asText()));
        }

        String tenant = first(text(canonical, "tenantId"), text(canonical, "tenant_id"),
                fields.get("tenant_id"), fields.get("tenantId"));
        if (!TenantContext.isValid(tenant)) {
            throw malformed("event tenant is required and must be valid");
        }
        fields.put("tenant_id", tenant);
        fieldsNode.put("tenant_id", tenant);

        String sourceEventId = first(text(canonical, "eventId"), text(canonical, "event_id"));
        if (sourceEventId == null) sourceEventId = positionEventId(sourceTopic, sourcePartition, sourceOffset);
        canonical.put("eventId", sourceEventId);
        canonical.put("tenantId", tenant);

        String source = first(text(canonical, "source"), "unknown");
        String host = first(text(canonical, "host"), "unknown");
        SecurityEvent event = new SecurityEvent(sourceEventId, parseTimestamp(canonical),
                source, host, first(text(canonical, "msg"), text(canonical, "message"), ""),
                Map.copyOf(fields), Severity.INFO);
        return new RouteContext(tenant, sourceEventId, event,
                sourceTopic, sourcePartition, sourceOffset);
    }

    private static String routedPayload(ObjectNode canonical, RouteContext context,
                                        DetectionRoutingPlan plan, ResolvedRoute route,
                                        String deliveryId, List<String> missing) {
        try {
            ObjectNode copy = canonical.deepCopy();
            ObjectNode fields = copy.withObject("/fields");
            fields.put("tenant_id", context.tenant());
            fields.put(DetectionDelivery.DELIVERY_ID_FIELD, deliveryId);
            fields.put(DetectionDelivery.SOURCE_EVENT_ID_FIELD, context.sourceEventId());
            fields.put(DetectionDelivery.KIND_FIELD, route.kind().name());
            fields.put(DetectionDelivery.DIMENSION_FIELD, route.dimension());
            fields.put(DetectionDelivery.VALUE_FIELD, route.value());
            fields.put(DetectionDelivery.ROUTING_VERSION_FIELD, plan.routingVersion());
            fields.put(DetectionDelivery.PLAN_VERSION_FIELD, plan.version());
            fields.put(DetectionDelivery.SCHEMA_VERSION_FIELD, DetectionDelivery.SCHEMA_VERSION);
            fields.put(DetectionDelivery.SOURCE_TOPIC_FIELD, context.sourceTopic());
            fields.put(DetectionDelivery.SOURCE_PARTITION_FIELD,
                    String.valueOf(context.sourcePartition()));
            fields.put(DetectionDelivery.SOURCE_OFFSET_FIELD,
                    String.valueOf(context.sourceOffset()));
            fields.put(DetectionRoutingKey.ROUTING_FIELD, route.dimension());
            fields.put(DetectionRoutingKey.ROUTING_VALUE, route.value());
            if (missing != null && !missing.isEmpty()) {
                fields.put(DetectionDelivery.MISSING_DIMENSIONS_FIELD, String.join(",", missing));
            } else {
                fields.remove(DetectionDelivery.MISSING_DIMENSIONS_FIELD);
            }
            return Json.mapper().writeValueAsString(copy);
        } catch (Exception failure) {
            throw new IllegalStateException("unable to serialize routed detection delivery", failure);
        }
    }

    private static ObjectNode parseObject(String raw) {
        try {
            JsonNode node = Json.mapper().readTree(raw);
            if (!(node instanceof ObjectNode object)) {
                throw malformed("canonical event payload must be an object");
            }
            return object;
        } catch (MalformedCanonicalEventException malformed) {
            throw malformed;
        } catch (IllegalArgumentException malformed) {
            throw malformed("invalid canonical event JSON", malformed);
        } catch (Exception failure) {
            throw malformed("invalid canonical event JSON", failure);
        }
    }

    private static Instant parseTimestamp(ObjectNode payload) {
        String value = first(text(payload, "timestamp"), text(payload, "@timestamp"));
        if (value == null) {
            throw malformed("canonical event timestamp is required");
        }
        try {
            return Instant.parse(value);
        } catch (Exception failure) {
            throw malformed("canonical event timestamp must be ISO-8601", failure);
        }
    }

    private static String positionEventId(String topic, int partition, long offset) {
        String tuple = topic + "\u0000" + partition + "\u0000" + offset;
        return "kafka-source:" + UUID.nameUUIDFromBytes(
                tuple.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() || "null".equalsIgnoreCase(text) ? null : text.trim();
    }

    private static String first(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String auditRouteValue(String value) {
        if (value == null || value.length() <= 1024) return value;
        try {
            String digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            return value.substring(0, 960) + "#sha256=" + digest;
        } catch (Exception failure) {
            return value.substring(0, 1024);
        }
    }

    private static MalformedCanonicalEventException malformed(String message) {
        return new MalformedCanonicalEventException(message, null);
    }

    private static MalformedCanonicalEventException malformed(String message, Throwable cause) {
        return new MalformedCanonicalEventException(message, cause);
    }

    private static String truncate(String value) {
        if (value == null) return "unknown";
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static final class MalformedCanonicalEventException extends IllegalArgumentException {
        private MalformedCanonicalEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record RouteContext(String tenant, String sourceEventId, SecurityEvent event,
                                String sourceTopic, int sourcePartition, long sourceOffset) {
    }

    private record ResolvedRoute(DetectionDelivery.Kind kind, String dimension, String value) {
    }

    public record RouteResult(String tenantId, String sourceEventId, String planVersion,
                              int deliveryCount, List<String> missingDimensions,
                              boolean terminalFailure) {
        public RouteResult {
            missingDimensions = missingDimensions == null ? List.of() : List.copyOf(missingDimensions);
        }
    }
}
