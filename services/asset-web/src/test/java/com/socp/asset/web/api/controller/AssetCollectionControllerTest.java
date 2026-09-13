package com.socp.asset.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.asset.web.domain.Asset;
import com.socp.asset.web.persistence.store.AssetStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssetCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "socp.security.dev-bypass=true")
class AssetCollectionControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private AssetStore store;

    @MockitoBean
    private SocpHttpClient http;

    @Test
    void collectionPersistsInOwningDomainAndForwardsCanonicalTenant() throws Exception {
        Asset saved = Asset.create("web-03", "SERVER", "10.0.0.30", "Linux", "sec", "HIGH");
        given(store.upsertByIp(org.mockito.ArgumentMatchers.any(Asset.class))).willReturn(saved);
        given(store.count()).willReturn(1L);
        given(http.post(eq(SocpService.SEARCH), eq("/api/v1/ingest"),
                org.mockito.ArgumentMatchers.anyString(), eq(SocpHttpClient.NDJSON), eq(5000)))
                .willReturn(new ServiceCall(SocpService.SEARCH, "http://search", true, 202,
                        "accepted", null, 1, false, 1));

                mvc.perform(post("/api/v1/collect")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "web-03", "ip", "10.0.0.30", "tenantId", "spoofed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.assetId").value(saved.id()))
                .andExpect(jsonPath("$.data.forwarded").value(true));

        verify(http).post(eq(SocpService.SEARCH), eq("/api/v1/ingest"),
                contains("\"tenantId\":\"tenant-a\""), eq(SocpHttpClient.NDJSON), eq(5000));
    }

    @Test
    void collectedUsesTenantScopedServerPaging() throws Exception {
        Asset item = Asset.create("web-04", "SERVER", "10.0.0.40", "Linux", "sec", "HIGH");
        given(store.page(2, 20, "web")).willReturn(
                new PageImpl<>(List.of(item), PageRequest.of(1, 20), 41));

        mvc.perform(get("/api/v1/collected")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst")
                        .header("X-Tenant-Id", "tenant-a")
                        .param("page", "2")
                        .param("size", "20")
                        .param("q", "  web  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        verify(store).page(2, 20, "web");
    }

    @Test
    void collectedRejectsAnUnboundedSearchQuery() throws Exception {
        mvc.perform(get("/api/v1/collected")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst")
                        .header("X-Tenant-Id", "tenant-a")
                        .param("q", "x".repeat(129)))
                .andExpect(status().isBadRequest());
    }
}
