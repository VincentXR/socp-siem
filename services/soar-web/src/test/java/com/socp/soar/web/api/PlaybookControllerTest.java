package com.socp.soar.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.service.PlaybookExecutor;
import com.socp.soar.web.store.PlaybookStore;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.socp.soar.web.service.AlarmEvaluationService;

/**
 * SOAR 剧本 API 切片测试（PlaybookStore / PlaybookExecutor 被 mock）。
 */
@WebMvcTest(PlaybookController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class PlaybookControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private PlaybookStore store;

    @MockitoBean
    private PlaybookExecutor executor;

    @MockitoBean
    private AlarmEvaluationService evaluationService;

    @Test
    void listSerialisesPlaybookWithActionsAndStatus() throws Exception {
        given(store.list()).willReturn(List.of(Playbook.create(
                "高危告警自动封禁", "告警 severity >= HIGH",
                List.of("查询资产归属", "下发防火墙封禁"), true)));

        mvc.perform(get("/api/v1/playbooks").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("高危告警自动封禁"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].actions.length()").value(2));
    }

    @Test
    void createDefaultsToDraftStatusWhenDisabled() throws Exception {
        given(store.save(any(Playbook.class))).willAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = Map.of(
                "name", "新剧本", "trigger", "告警 severity >= CRITICAL",
                "actions", List.of("通知值班群"), "enabled", false);

        mvc.perform(post("/api/v1/playbooks")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新剧本"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void evaluateDelegatesToExecutor() throws Exception {
        given(evaluationService.evaluate(anyMap())).willReturn(Map.of(
                "alarmId", "AL-1", "triggered", 1, "playbooks", List.of(Map.of("playbook", "高危告警自动封禁"))));

        mvc.perform(post("/api/v1/playbooks/evaluate")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "id", "AL-1", "ruleId", "AUTH-BRUTE", "severity", "HIGH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alarmId").value("AL-1"))
                .andExpect(jsonPath("$.triggered").value(1))
                .andExpect(jsonPath("$.playbooks[0].playbook").value("高危告警自动封禁"));
    }

    @Test
    void toggleOnUnknownPlaybookYieldsEmptyBody() throws Exception {
        given(store.toggle("ghost")).willReturn(null);

        mvc.perform(post("/api/v1/playbooks/{id}/toggle", "ghost")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }
}
