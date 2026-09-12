package com.socp.soc.api.controller;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soc.service.AuditQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Transport-level contract tests for the audit query endpoints. */
@WebMvcTest(AuditController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class AuditControllerTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditQueryService queryService;

    @Test
    void recordsReturnedWithFilter() throws Exception {
        given(queryService.records(50, "CREATE")).willReturn(Map.of(
                "total", 1,
                "returned", 1,
                "records", List.of(Map.of("action", "CREATE_IOC", "result", "SUCCESS"))));

        mvc.perform(get("/api/v1/audit/records?limit=50&action=CREATE")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.items[0].action").value("CREATE_IOC"))
                .andExpect(jsonPath("$.data.items[0].result").value("SUCCESS"));
    }

    @Test
    void statsAggregateByAction() throws Exception {
        given(queryService.stats()).willReturn(Map.of(
                "total", 2,
                "byAction", Map.of("CREATE_IOC", 1, "CREATE_ALARM", 1),
                "byResult", Map.of("SUCCESS", 1)));

        mvc.perform(get("/api/v1/audit/stats")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.byAction.CREATE_IOC").value(1))
                .andExpect(jsonPath("$.data.byAction.CREATE_ALARM").value(1))
                .andExpect(jsonPath("$.data.byResult.SUCCESS").value(1));
    }
}
