package com.socp.asset.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.asset.web.api.request.AssetCollectionRequest;
import com.socp.asset.web.domain.Asset;
import com.socp.asset.web.persistence.store.AssetStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 资产 CRUD Web 层切片测试（AssetStore 被 mock）。
 */
@WebMvcTest(AssetController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class AssetControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private AssetStore store;

    @Test
    void relatedLookupValidatesIdentityBoundsAndAuthorization() throws Exception {
        var asset = Asset.create("host", "SERVER", "203.0.113.7", "Linux", "sec", "HIGH");
        given(store.related(2, 20, "203.0.113.7", "host")).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(asset), org.springframework.data.domain.PageRequest.of(1, 20), 21));
        mvc.perform(get("/api/v1/assets/related").param("ip", " 203.0.113.7 ").param("name", " host ").param("page", "2")
                        .header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.items[0].id").value(asset.id()));
        for (String query : List.of("", "?ip=" + "x".repeat(65), "?name=" + "x".repeat(129), "?name=host&size=501", "?name=host&page=0")) {
            mvc.perform(get("/api/v1/assets/related" + query).header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/assets/related?name=host").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "viewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsPagedEnvelope() throws Exception {
        given(store.page(1, 500, "")).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(Asset.create("web01", "SERVER", "10.0.0.5", "Ubuntu 22.04", "infra", "HIGH")),
                org.springframework.data.domain.PageRequest.of(0, 500), 1));

        mvc.perform(get("/api/v1/assets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(500))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("web01"))
                .andExpect(jsonPath("$.data.items[0].ip").value("10.0.0.5"))
                .andExpect(jsonPath("$.data.items[0].criticality").value("HIGH"));
    }

    @Test
    void directDetailUsesTheOwningStoreAndMissingIdsReturn404() throws Exception {
        Asset asset = new Asset("outside-page", "linked-host", "SERVER", "203.0.113.7", "Linux", "sec", "HIGH", java.time.Instant.now());
        given(store.get("outside-page")).willReturn(asset);
        mvc.perform(get("/api/v1/assets/outside-page").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("outside-page"));
        mvc.perform(get("/api/v1/assets/missing").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "analyst"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/assets/outside-page").header(HttpHeaders.AUTHORIZATION, BEARER).header("X-Role", "viewer"))
                .andExpect(status().isForbidden());
        verify(store).get("outside-page");
    }

    @Test
    void listRejectsSizeAboveConfiguredLimit() throws Exception {
        mvc.perform(get("/api/v1/assets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .param("page", "1")
                        .param("size", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listPaginatesByExplicitPageAndSize() throws Exception {
        java.time.Instant createdAt = java.time.Instant.now();
        given(store.page(2, 2, "")).willReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(new Asset("a-3", "three", "SERVER", "10.0.0.3", "", "sec", "HIGH", createdAt)),
                org.springframework.data.domain.PageRequest.of(1, 2), 3));

        mvc.perform(get("/api/v1/assets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value("a-3"));
    }

    @Test
    void createMapsRequestBodyToAsset() throws Exception {
        given(store.save(any(Asset.class))).willAnswer(inv -> inv.getArgument(0));

        Map<String, String> body = Map.of(
                "name", "kafka-2", "type", "MESSAGE", "ip", "10.0.0.21",
                "os", "Kafka 4.0", "owner", "infra", "criticality", "HIGH");

        mvc.perform(post("/api/v1/assets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("kafka-2"))
                .andExpect(jsonPath("$.data.type").value("MESSAGE"))
                .andExpect(jsonPath("$.data.owner").value("infra"))
                .andExpect(jsonPath("$.data.id").isNotEmpty());

        verify(store).save(any(Asset.class));
    }

    @Test
    void importReportsImportedAndSkippedRows() throws Exception {
        given(store.save(any(Asset.class))).willAnswer(inv -> inv.getArgument(0));
        List<Map<String, String>> rows = List.of(
                Map.of("name", "web-imported", "type", "SERVER", "ip", "10.0.0.40"),
                Map.of("name", "missing-ip"));

        mvc.perform(post("/api/v1/assets/import")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(rows)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.containsString("缺少名称或 IP")));

        verify(store).save(any(Asset.class));
    }

    @Test
    void collectUsesSafeDefaultsAndReturnsAcceptedEnvelope() throws Exception {
        Asset saved = Asset.create("collector-1", "SERVER", "", "", "collect", "HIGH");
        given(store.upsertByIp(any(Asset.class))).willReturn(saved);
        given(store.count()).willReturn(1L);

        mvc.perform(post("/api/v1/assets/collect")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AssetCollectionRequest("collector-1",
                                null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.assetId").value(saved.id()))
                .andExpect(jsonPath("$.data.total").value(1));

        verify(store).upsertByIp(any(Asset.class));
    }

    @Test
    void deleteReportsWhetherAssetExisted() throws Exception {
        given(store.delete("known")).willReturn(true);
        given(store.delete("ghost")).willReturn(false);

        mvc.perform(delete("/api/v1/assets/{id}", "known")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));

        mvc.perform(delete("/api/v1/assets/{id}", "ghost")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(false));
    }

    @Test
    void updateKeepsAssetIdAndCreatedAt() throws Exception {
        Asset existing = Asset.create("old-name", "SERVER", "10.0.0.30", "Ubuntu 22.04", "infra", "HIGH");
        given(store.get("asset-1")).willReturn(new Asset("asset-1", existing.name(), existing.type(), existing.ip(), existing.os(), existing.owner(), existing.criticality(), existing.createdAt()));
        given(store.save(any(Asset.class))).willAnswer(inv -> inv.getArgument(0));

        Map<String, String> body = Map.of(
                "name", "web-prod-01", "type", "SERVER", "ip", "10.0.0.31",
                "os", "Ubuntu 24.04", "owner", "sec", "criticality", "CRITICAL");

        mvc.perform(put("/api/v1/assets/{id}", "asset-1")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("asset-1"))
                .andExpect(jsonPath("$.data.name").value("web-prod-01"))
                .andExpect(jsonPath("$.data.criticality").value("CRITICAL"));

        verify(store).save(any(Asset.class));
    }

    @Test
    void statsNormalizesNullAndBlankDimensionsToUnknown() throws Exception {
        given(store.stats()).willReturn(Map.of(
                "total", 3L,
                "byType", Map.of("UNKNOWN", 1L, "SERVER", 2L),
                "byOwner", Map.of("UNKNOWN", 1L, "sec", 2L),
                "byCriticality", Map.of("UNKNOWN", 2L, "HIGH", 1L)));

        mvc.perform(get("/api/v1/assets/stats").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.byType.UNKNOWN").value(1))
                .andExpect(jsonPath("$.data.byType.SERVER").value(2))
                .andExpect(jsonPath("$.data.byOwner.UNKNOWN").value(1))
                .andExpect(jsonPath("$.data.byCriticality.UNKNOWN").value(2));
    }
}
