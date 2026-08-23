package com.socp.asset.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.asset.web.model.Asset;
import com.socp.asset.web.store.AssetStore;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.platform.client.SocpService;
import com.socp.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asset collection ingress hosted by the asset domain deployment.
 *
 * <p>The legacy {@code /asset-collect/**} gateway route is rewritten to this
 * controller, so collection does not require a dedicated JVM. The standalone
 * {@code asset-collect} module remains available as a compatibility launcher.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class AssetCollectionController {

    private static final Logger log = LoggerFactory.getLogger(AssetCollectionController.class);

    private final AssetStore store;
    private final SocpHttpClient http;
    private final ObjectMapper objectMapper;

    public AssetCollectionController(AssetStore store, SocpHttpClient http, ObjectMapper objectMapper) {
        this.store = store;
        this.http = http;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/collect")
    public Map<String, Object> collect(@RequestBody Map<String, Object> input) {
        Map<String, Object> event = canonicalEvent(input);
        Asset saved = store.upsertByIp(Asset.create(
                text(input, "name", "unknown"),
                text(input, "type", "SERVER"),
                text(input, "ip", ""),
                text(input, "os", ""),
                text(input, "owner", "collect"),
                text(input, "criticality", "HIGH")));

        ServiceCall forward = http.post(SocpService.SEARCH, "/api/v1/ingest", serialize(event),
                SocpHttpClient.NDJSON, 5000);
        if (!forward.ok()) {
            log.warn("Asset collection event forwarding failed id={} reason={}",
                    event.get("id"), forward.failureReason());
        }
        return Map.of(
                "accepted", true,
                "assetId", saved.id(),
                "total", store.list().size(),
                "forwarded", forward.ok());
    }

    @GetMapping({"/collected", "/discovered"})
    public List<Asset> collected() {
        return store.list();
    }

    private Map<String, Object> canonicalEvent(Map<String, Object> input) {
        Map<String, Object> event = new LinkedHashMap<>(input);
        event.put("id", UUID.randomUUID().toString());
        event.put("collectedAt", Instant.now().toString());
        event.put("event.category", "asset");
        event.put("event.action", "discover");
        event.put("tenantId", tenant());
        return event;
    }

    private String serialize(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize asset collection event", ex);
        }
    }

    private static String text(Map<String, Object> input, String name, String fallback) {
        Object value = input.get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
