package com.socp.soc.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合规看板 API 切片测试：控制项目录为内置静态数据，覆盖率算法必须与规则 ID 映射一致。
 */
@WebMvcTest(ComplianceController.class)
@AutoConfigureMockMvc(addFilters = false)
class ComplianceControllerTest {

    private static final String BEARER = "Bearer test-token";

    /** 内置框架控制项总数，覆盖率分母。改动 FRAMEWORKS 时需同步。 */
    private static final int TOTAL_CONTROLS = 28;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void frameworksExposeAllBuiltInCatalogs() throws Exception {
        mvc.perform(get("/api/v1/compliance/frameworks").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameworks.length()").value(5))
                .andExpect(jsonPath("$.frameworks[0].name").value("PCI-DSS"))
                .andExpect(jsonPath("$.frameworks[0].controls[0].id").value("PCI-10.2"))
                .andExpect(jsonPath("$.frameworks[0].controls[0].ruleIds").isArray());
    }

    @Test
    void coverageMarksControlsHitByEnabledRules() throws Exception {
        // AUTH-BRUTE 命中 PCI-10.2 / HIPAA-164.312(b) / A.8.15 / DJCP-8.1.4.3；
        // WEB-ATTACK 命中 PCI-10.6 / PCI-6.5 / HIPAA-164.308(a)(1) / A.8.8 / GDPR-Art.33 / DJCP-8.1.4.4
        String body = json.writeValueAsString(Map.of("ruleIds", List.of("AUTH-BRUTE", "WEB-ATTACK")));

        mvc.perform(post("/api/v1/compliance/coverage")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalControls").value(TOTAL_CONTROLS))
                .andExpect(jsonPath("$.coveredControls").value(10))
                .andExpect(jsonPath("$.coverage").value(36))
                .andExpect(jsonPath("$.byFramework.length()").value(5))
                .andExpect(jsonPath("$.byFramework[0].framework").value("PCI-DSS"))
                .andExpect(jsonPath("$.byFramework[0].coverage").value(43))
                .andExpect(jsonPath("$.byFramework[0].controls[0].covered").value(true))
                .andExpect(jsonPath("$.byFramework[0].controls[1].covered").value(false));
    }

    @Test
    void coverageIsZeroWhenNoRulesEnabled() throws Exception {
        String body = json.writeValueAsString(Map.of("ruleIds", List.of()));

        mvc.perform(post("/api/v1/compliance/coverage")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalControls").value(TOTAL_CONTROLS))
                .andExpect(jsonPath("$.coveredControls").value(0))
                .andExpect(jsonPath("$.coverage").value(0));
    }
}
