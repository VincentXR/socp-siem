package com.socp.asset.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.asset.web.model.Asset;
import com.socp.asset.web.store.AssetStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 资产 CRUD Web 层切片测试（AssetStore 被 mock）。
 */
@WebMvcTest(AssetController.class)
@AutoConfigureMockMvc(addFilters = false)
class AssetControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private AssetStore store;

    @Test
    void listReturnsAssets() throws Exception {
        given(store.list()).willReturn(List.of(
                Asset.create("web01", "SERVER", "10.0.0.5", "Ubuntu 22.04", "infra", "HIGH")));

        mvc.perform(get("/api/v1/assets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("web01"))
                .andExpect(jsonPath("$[0].ip").value("10.0.0.5"))
                .andExpect(jsonPath("$[0].criticality").value("HIGH"));
    }

    @Test
    void createMapsRequestBodyToAsset() throws Exception {
        given(store.save(any(Asset.class))).willAnswer(inv -> inv.getArgument(0));

        Map<String, String> body = Map.of(
                "name", "kafka-2", "type", "MESSAGE", "ip", "10.0.0.21",
                "os", "Kafka 4.0", "owner", "infra", "criticality", "HIGH");

        mvc.perform(post("/api/v1/assets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("kafka-2"))
                .andExpect(jsonPath("$.type").value("MESSAGE"))
                .andExpect(jsonPath("$.owner").value("infra"))
                .andExpect(jsonPath("$.id").isNotEmpty());

        verify(store).save(any(Asset.class));
    }

    @Test
    void deleteReportsWhetherAssetExisted() throws Exception {
        given(store.delete("known")).willReturn(true);
        given(store.delete("ghost")).willReturn(false);

        mvc.perform(delete("/api/v1/assets/{id}", "known")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));

        mvc.perform(delete("/api/v1/assets/{id}", "ghost")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(false));
    }
}
