package com.socp.hips.web.store;

import com.socp.platform.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tenant-scoped recent endpoint events shared by the endpoint and collection APIs. */
@Component
public class EndpointEventStore {

    private final EndpointStore endpoints;
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

    public EndpointEventStore(EndpointStore endpoints) {
        this.endpoints = endpoints;
    }

    public Map<String, Object> add(Map<String, Object> input) {
        Map<String, Object> event = new LinkedHashMap<>(input);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("receivedAt", Instant.now().toString());
        event.put("tenantId", tenant());
        events.add(event);

        String hostname = String.valueOf(input.getOrDefault("hostname", ""));
        endpoints.list().stream()
                .filter(endpoint -> endpoint.hostname().equals(hostname))
                .findFirst()
                .ifPresent(endpoint -> endpoints.heartbeat(endpoint.id()));
        return new LinkedHashMap<>(event);
    }

    public List<Map<String, Object>> list() {
        String selected = tenant();
        return events.stream().filter(event -> selected.equals(event.get("tenantId"))).toList();
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
