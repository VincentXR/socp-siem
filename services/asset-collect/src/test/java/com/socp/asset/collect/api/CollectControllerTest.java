package com.socp.asset.collect.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.asset.collect.collector.AssetScanner;
import com.socp.asset.collect.store.AssetCollectionStore;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.platform.client.SocpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ASSET 采集入口切片测试：上报 → 持久化包 → 转发状态 → 回读。
 */
@WebMvcTest(CollectController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class CollectControllerTest {

    private static final String BEARER = "Bearer test-token";

    @MockitoBean
    private AssetScanner scanner;

    @MockitoBean
    private SocpHttpClient http;

    @MockitoBean
    private AssetCollectionStore store;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void collectEnrichesRecordAndIsReadableBack() throws Exception {
        List<Map<String, Object>> persisted = new ArrayList<>();
        when(store.append(anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> record = new LinkedHashMap<>(invocation.getArgument(1));
            record.put("id", "asset-" + (persisted.size() + 1));
            record.put("collectedAt", Instant.now().toString());
            record.put("tenantId", "default");
            persisted.add(record);
            return record;
        });
        when(store.count(anyString())).thenAnswer(invocation -> (long) persisted.size());
        when(store.list(anyString())).thenAnswer(invocation -> List.copyOf(persisted));
        when(http.post(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new ServiceCall(SocpService.SEARCH, "/api/v1/ingest", true,
                        200, "", null, 1, false, 1));

        String body = json.writeValueAsString(Map.of(
                "name", "cmdb-host-1", "type", "SERVER", "ip", "10.0.0.77"));

        mvc.perform(post("/api/v1/collect")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("FORWARDED"))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(post("/api/v1/collect")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "cmdb-host-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/v1/collected")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("cmdb-host-1"))
                .andExpect(jsonPath("$[0].ip").value("10.0.0.77"))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].collectedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("cmdb-host-2"));
    }
}
