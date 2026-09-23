package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.detect.web.persistence.entity.DetectionRouteSourceEntity;
import com.socp.detect.web.persistence.entity.DetectionRouteTopologyEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import com.socp.detect.web.persistence.repository.DetectionRouteSourceRepository;
import com.socp.detect.web.persistence.repository.DetectionRouteTopologyRepository;
import com.socp.rule.partition.DetectionDelivery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionRouteOutboxServiceTest {

    @Test
    void fansOutOncePerDimensionNotOncePerRule() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        when(registry.plan("tenant-a")).thenReturn(plan(8));
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(repository, registry, "socp-detection-routed-v2");

        var result = service.route("socp-events", 2, 41L, eventJson(true));

        assertEquals(3, result.deliveryCount(),
                "singleton stateless/control + shared user copy + shared src_ip copy");
        assertTrue(result.missingDimensions().isEmpty());

        List<DetectionRouteOutboxEntity> rows = savedRows(repository);
        assertEquals(3, rows.size());
        assertEquals(1, rows.stream().filter(row ->
                "STATELESS".equals(row.getRouteKind())).count());
        assertEquals(1, rows.stream().filter(row ->
                "user".equals(row.getRouteDimension())).count(),
                "two user rules must share one user delivery");
        assertEquals(1, rows.stream().filter(row ->
                "src_ip".equals(row.getRouteDimension())).count());
        assertEquals(3, rows.stream().map(DetectionRouteOutboxEntity::getDeliveryId)
                .distinct().count());
        assertTrue(rows.stream().allMatch(row -> row.getSourceEventId().equals("source-41")));
        assertTrue(rows.stream().allMatch(row -> row.getSourcePartition() == 2
                && row.getSourceOffset() == 41L));
    }

    @Test
    void missingDimensionIsExplicitAndDoesNotInventAFallbackValue() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        when(registry.plan("tenant-a")).thenReturn(plan(8));
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(repository, registry, "socp-detection-routed-v2");

        var result = service.route("socp-events", 1, 9L, eventJson(false));

        assertEquals(List.of("user"), result.missingDimensions());
        assertEquals(2, result.deliveryCount(),
                "missing user omits only the user state copy; source still has durable control + src_ip copies");
        List<DetectionRouteOutboxEntity> rows = savedRows(repository);
        assertFalse(rows.stream().anyMatch(row -> "user".equals(row.getRouteDimension())));
        assertTrue(rows.stream().allMatch(row ->
                row.getPayload().contains(DetectionDelivery.MISSING_DIMENSIONS_FIELD)
                        && row.getPayload().contains("user")));
    }

    @Test
    void unsupportedRuleDeploymentPinsSourceInsteadOfPublishingPartialPlan() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        when(registry.plan("tenant-a")).thenReturn(plan(1));
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(repository, registry, "socp-detection-routed-v2");

        assertThrows(DetectionRoutingPlan.UnsupportedRoutingPlanException.class,
                () -> service.route("socp-events", 0, 1L, eventJson(true)));
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void replayAfterOutboxCommitReusesFrozenPlanEvenIfRulesChanged() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        DetectionRouteOutboxEntity frozen = new DetectionRouteOutboxEntity(
                "delivery-frozen", "tenant-a", "source-41",
                DetectionDelivery.ROUTING_VERSION, "route-plan-old",
                "STATEFUL", "user", "alice", "tenant-a|user|alice",
                "socp-events", 2, 41L, "socp-detection-routed-v2", "{}", java.time.Instant.now());
        frozen.setStatus("PUBLISHED");
        when(repository.findBySourceTopicAndSourcePartitionAndSourceOffsetOrderByDeliveryIdAsc(
                "socp-events", 2, 41L)).thenReturn(List.of(frozen));
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(repository, registry, "socp-detection-routed-v2");

        var result = service.route("socp-events", 2, 41L, eventJson(true));

        assertEquals("route-plan-old", result.planVersion());
        assertEquals(1, result.deliveryCount());
        verify(registry, never()).plan(anyString());
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void duplicateBusinessEventAtAnotherKafkaOffsetGetsAReceiptWithoutNewFanOut() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRouteSourceRepository sources = mock(DetectionRouteSourceRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        when(registry.plan("tenant-a")).thenReturn(plan(8));
        DetectionRouteOutboxEntity existing = new DetectionRouteOutboxEntity(
                "delivery-existing", "tenant-a", "source-41",
                DetectionDelivery.ROUTING_VERSION, plan(8).version(),
                "STATEFUL", "user", "alice", "tenant-a|user|alice",
                "socp-events", 1, 10L, "socp-detection-routed-v2", "{}",
                java.time.Instant.EPOCH);
        when(repository.findByTenantIdAndSourceEventIdOrderByDeliveryIdAsc(
                "tenant-a", "source-41")).thenReturn(List.of(existing));
        DetectionRouteOutboxService service = new DetectionRouteOutboxService(
                repository, registry, sources, null,
                "socp-detection-routed-v2", null);

        var result = service.route("socp-events", 4, 99L, eventJson(true));

        assertEquals(1, result.deliveryCount());
        verify(repository, never()).saveAllAndFlush(any());
        verify(sources).saveAndFlush(any(DetectionRouteSourceEntity.class));
    }

    @Test
    void exactSourcePositionReceiptSkipsPlanRecomputation() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRouteSourceRepository sources = mock(DetectionRouteSourceRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        DetectionRouteSourceEntity receipt = new DetectionRouteSourceEntity(
                "tenant-a", "socp-events", 2, 41L, "source-41",
                DetectionDelivery.ROUTING_VERSION, "route-plan-frozen", 3,
                "user", "ROUTED", null, java.time.Instant.EPOCH);
        when(sources.findByTenantIdAndSourceTopicAndSourcePartitionAndSourceOffset(
                "tenant-a", "socp-events", 2, 41L))
                .thenReturn(java.util.Optional.of(receipt));
        DetectionRouteOutboxService service = new DetectionRouteOutboxService(
                repository, registry, sources, null,
                "socp-detection-routed-v2", null);

        var result = service.route("socp-events", 2, 41L, eventJson(true));

        assertEquals("route-plan-frozen", result.planVersion());
        assertEquals(List.of("user"), result.missingDimensions());
        verify(registry, never()).plan(anyString());
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void topologyChangeUnderSameRoutingVersionFailsClosed() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRouteTopologyRepository topology = mock(DetectionRouteTopologyRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        DetectionRoutingPlan current = plan(8);
        when(registry.plan("tenant-a")).thenReturn(current);
        when(topology.findByTenantIdAndRoutingVersion(
                "tenant-a", DetectionDelivery.ROUTING_VERSION))
                .thenReturn(java.util.Optional.of(new DetectionRouteTopologyEntity(
                        "tenant-a", DetectionDelivery.ROUTING_VERSION,
                        "route-plan-old", java.time.Instant.EPOCH)));
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(
                        repository, registry, topology, "socp-detection-routed-v2");

        var failure = assertThrows(
                DetectionRoutingPlan.UnsupportedRoutingPlanException.class,
                () -> service.route("socp-events", 3, 71L, eventJson(true)));

        assertTrue(failure.getMessage().contains("pinnedPlan=route-plan-old"));
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void malformedCanonicalPayloadCreatesTerminalRouteEvidence() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(repository, registry, "socp-detection-routed-v2");

        var result = service.route("socp-events", 0, 8L, null);

        assertTrue(result.terminalFailure());
        verify(repository).saveAndFlush(any(DetectionRouteOutboxEntity.class));
        verify(registry, never()).plan(anyString());
    }

    @Test
    void persistenceFailureRemainsRetryableAndIsNotConvertedIntoRouteError() {
        DetectionRouteOutboxRepository repository = mock(DetectionRouteOutboxRepository.class);
        DetectionRoutingPlanRegistry registry = mock(DetectionRoutingPlanRegistry.class);
        when(registry.plan("tenant-a")).thenReturn(plan(8));
        when(repository.saveAllAndFlush(any())).thenThrow(new IllegalStateException("db unavailable"));
        DetectionRouteOutboxService service =
                new DetectionRouteOutboxService(repository, registry, "socp-detection-routed-v2");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.route("socp-events", 0, 2L, eventJson(true)));
        assertTrue(failure.getMessage().contains("db unavailable"));
        verify(repository).saveAllAndFlush(any());
    }

    private static DetectionRoutingPlan plan(int maxDimensions) {
        List<Map<String, Object>> rules = List.of(
                rule("USER-1", "user"),
                rule("USER-2", "user"),
                rule("SRC-1", "src_ip"));
        return DetectionRoutingPlan.compile(rules, maxDimensions);
    }

    private static Map<String, Object> rule(String id, String dimension) {
        return Map.of(
                "id", id,
                "name", id,
                "type", "threshold",
                "severity", "HIGH",
                "threshold", 2,
                "status", "ACTIVE",
                "version", "1",
                "groupBy", dimension,
                "routingField", dimension,
                "dataSources", List.of("auth"));
    }

    private static String eventJson(boolean includeUser) {
        String user = includeUser ? ",\"user\":\"alice\"" : "";
        return "{"
                + "\"eventId\":\"source-41\","
                + "\"tenantId\":\"tenant-a\","
                + "\"source\":\"auth\","
                + "\"host\":\"host-a\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"msg\":\"login\","
                + "\"fields\":{\"src_ip\":\"198.51.100.41\"" + user + "}"
                + "}";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<DetectionRouteOutboxEntity> savedRows(
            DetectionRouteOutboxRepository repository) {
        ArgumentCaptor<Iterable> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAllAndFlush(captor.capture());
        List<DetectionRouteOutboxEntity> rows = new ArrayList<>();
        captor.getValue().forEach(value -> rows.add((DetectionRouteOutboxEntity) value));
        return rows;
    }
}
