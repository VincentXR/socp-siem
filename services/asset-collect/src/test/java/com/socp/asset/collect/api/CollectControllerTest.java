package com.socp.asset.collect.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ASSET 采集入口切片测试：上报 → 富化（id / collectedAt）→ 回读。
 * 采集缓冲是控制器单例上的内存状态，因此整条链路放在同一个测试方法里断言，
 * 避免依赖 JUnit 的方法执行顺序。
 */
@WebMvcTest(CollectController.class)
@AutoConfigureMockMvc(addFilters = false)
class CollectControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void collectEnrichesRecordAndIsReadableBack() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", "cmdb-host-1", "type", "SERVER", "ip", "10.0.0.77"));

        mvc.perform(post("/api/v1/collect")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
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
