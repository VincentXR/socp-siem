package com.socp.asset.collect.collector;

import com.socp.asset.collect.config.AssetCollectProperties;
import com.socp.asset.collect.persistence.store.AssetCollectionStore;
import com.socp.platform.client.service.AssetClient;
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

/** Development asset simulator with a durable collection buffer. */
@Component
@EnableScheduling
public class AssetScanner {

    private static final Logger log = LoggerFactory.getLogger(AssetScanner.class);

    private int round;
    private final AssetClient assetClient;
    private final AssetCollectionStore collectionStore;
    private final AssetCollectProperties properties;
    private final Environment environment;

    public AssetScanner(AssetClient assetClient, AssetCollectionStore collectionStore,
                        AssetCollectProperties properties, Environment environment) {
        this.assetClient = assetClient;
        this.collectionStore = collectionStore;
        this.properties = properties;
        this.environment = environment;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scan() {
        TenantContext.runWith("default", this::scanDefaultTenant);
    }

    private void scanDefaultTenant() {
        if (!simulationAllowed()) {
            log.info("asset-collect simulator is disabled; waiting for an Agent/CMDB source");
            return;
        }
        round++;
        String[] hosts = {"app-node-" + round, "cache-node-" + round, "lb-" + round};
        for (String name : hosts) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("name", name);
            asset.put("type", pick("SERVER", "SERVER", "LOADBALANCER", "DATABASE"));
            asset.put("ip", "10.0.10." + (10 + round));
            asset.put("os", "Ubuntu 22.04");
            asset.put("owner", "platform");
            asset.put("criticality", round % 3 == 0 ? "CRITICAL" : "HIGH");
            asset.put("scannedAt", Instant.now().toString());
            asset.put("tenantId", "default");
            asset.put("source", "simulator");

            Map<String, Object> persisted = collectionStore.append("default", asset);
            ServiceCall call = assetClient.collect(toJson(persisted));
            if (!call.ok()) {
                log.warn("asset forwarding failed name={} reason={}", name, call.failureReason());
            }
        }
        log.info("asset scan round {} completed and persisted", round);
    }

    public List<Map<String, Object>> discovered() {
        if (!simulationAllowed()) return List.of();
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) tenant = "default";
        return collectionStore.listBySource(tenant, "simulator");
    }

    private boolean simulationAllowed() {
        if (!properties.isSimulationEnabled()) return false;
        return java.util.Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(java.util.Locale.ROOT))
                .noneMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private static String pick(String... values) {
        return values[(int) (Math.random() * values.length)];
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
