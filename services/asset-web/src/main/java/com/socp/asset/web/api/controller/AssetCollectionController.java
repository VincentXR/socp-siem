package com.socp.asset.web.api.controller;

import com.socp.asset.web.api.request.AssetCollectionRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.asset.web.domain.Asset;
import com.socp.asset.web.persistence.store.AssetStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asset collection ingress hosted by the asset domain deployment.
 *
 * <p>The legacy {@code /asset-collect/**} gateway route is rewritten to this
 * controller, so collection does not require a dedicated JVM.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class AssetCollectionController {

    private static final Logger log = LoggerFactory.getLogger(AssetCollectionController.class);

    private final AssetStore store;
    private final SocpHttpClient http;
    private final ObjectMapper objectMapper;
    private final int maxListSize;

    public AssetCollectionController(AssetStore store, SocpHttpClient http, ObjectMapper objectMapper,
                                     @Value("${socp.web.list-max-size:500}") int maxListSize) {
        this.store = store;
        this.http = http;
        this.objectMapper = objectMapper;
        this.maxListSize = maxListSize;
    }

    @com.socp.platform.auth.security.RequireRole({"admin", "analyst"})
    @PostMapping("/collect")
    public ApiResult<Map<String, Object>> collect(@Valid @RequestBody AssetCollectionRequest input) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", input.name());
        source.put("type", input.type());
        source.put("ip", input.ip());
        source.put("os", input.os());
        source.put("owner", input.owner());
        source.put("criticality", input.criticality());
        Map<String, Object> event = canonicalEvent(source);
        Asset saved = store.upsertByIp(Asset.create(
                valueOr(input.name(), "unknown"),
                valueOr(input.type(), "SERVER"),
                valueOr(input.ip(), ""),
                valueOr(input.os(), ""),
                valueOr(input.owner(), "collect"),
                valueOr(input.criticality(), "HIGH")));

        ServiceCall forward = http.post(SocpService.SEARCH, "/api/v1/ingest", serialize(event),
                SocpHttpClient.NDJSON, 5000);
        if (!forward.ok()) {
            log.warn("Asset collection event forwarding failed id={} reason={}",
                    event.get("id"), forward.failureReason());
        }
        return ApiResult.ok(Map.of(
                "accepted", true,
                "assetId", saved.id(),
                "total", store.list().size(),
                "forwarded", forward.ok()));
    }

    /** 已采集资产列表：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    @GetMapping({"/collected", "/discovered"})
    public ApiResult<PageResponse<Asset>> collected(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Asset> all = store.list();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 0 || size > maxListSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
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

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
