package com.socp.hips.collect.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HIPS 运行时事件采集切片测试：Falco 风格事件上报 → 富化（id / receivedAt）→ 回读。
 * 事件缓冲是控制器单例上的内存状态，整条链路放在同一个测试方法内断言。
 */
@WebMvcTest(EventCollectController.class)
@AutoConfigureMockMvc(addFilters = false)
class EventCollectControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void reportEnrichesEventAndKeepsOriginalFields() throws Exception {
        Map<String, Object> falcoEvent = new LinkedHashMap<>();
        falcoEvent.put("rule", "Terminal shell in container");
        falcoEvent.put("priority", "Warning");
        falcoEvent.put("hostname", "web01");
        falcoEvent.put("output", "A shell was spawned in a container");

        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(falcoEvent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rule").value("Terminal shell in container"))
                .andExpect(jsonPath("$[0].priority").value("Warning"))
                .andExpect(jsonPath("$[0].hostname").value("web01"))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].receivedAt").isNotEmpty());
    }
}
