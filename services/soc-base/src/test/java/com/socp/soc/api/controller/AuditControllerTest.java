package com.socp.soc.api.controller;

import com.socp.soc.api.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.socp.soc.persistence.repository.AuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计日志查询 API 切片测试（AuditSink 被 mock）。
 * 测试环境显式开 dev-bypass（不配 jwt-secret），拦截器只要求 Bearer 非空。
 */
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
    private AuditSink sink;

    @MockitoBean
    private AuditRepository repository;

    @Test
    void recordsReturnedWithFilter() throws Exception {
        AuditRecord rec = new AuditRecord("default", "CREATE_IOC", "system", "threat",
                "SUCCESS", java.time.Instant.parse("2026-08-09T10:00:00Z"));
        given(sink.recent("default", 50, "CREATE")).willReturn(List.of(rec));
        given(sink.size("default")).willReturn(1);

        mvc.perform(get("/api/v1/audit/records?limit=50&action=CREATE")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.returned").value(1))
                .andExpect(jsonPath("$.records[0].action").value("CREATE_IOC"))
                .andExpect(jsonPath("$.records[0].result").value("SUCCESS"));
    }

    @Test
    void statsAggregateByAction() throws Exception {
        AuditRecord a = new AuditRecord("default", "CREATE_IOC", "system", "threat",
                "SUCCESS", java.time.Instant.parse("2026-08-09T10:00:00Z"));
        AuditRecord b = new AuditRecord("default", "CREATE_ALARM", "system", "t_alarm",
                "FAIL:bad", java.time.Instant.parse("2026-08-09T10:01:00Z"));
        given(sink.recent("default", 100_000, null)).willReturn(List.of(a, b));
        given(sink.size("default")).willReturn(2);

        mvc.perform(get("/api/v1/audit/stats")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byAction.CREATE_IOC").value(1))
                .andExpect(jsonPath("$.byAction.CREATE_ALARM").value(1))
                .andExpect(jsonPath("$.byResult.SUCCESS").value(1));
    }
}
