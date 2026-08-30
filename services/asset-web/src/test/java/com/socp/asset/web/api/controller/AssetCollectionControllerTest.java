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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        given(store.list()).willReturn(List.of(saved));
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
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.assetId").value(saved.id()))
                .andExpect(jsonPath("$.forwarded").value(true));

        verify(http).post(eq(SocpService.SEARCH), eq("/api/v1/ingest"),
                contains("\"tenantId\":\"tenant-a\""), eq(SocpHttpClient.NDJSON), eq(5000));
    }
}
