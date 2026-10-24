package com.socp.asset.web.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台统一响应体（com.socp.platform.error.api.ApiResult）契约测试：
 * 成功出口必须是 {code:0, message:"ok", data:{...}}，前端/网关按此统一拦截。
 */
@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class HealthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthReturnsApiResultEnvelope() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.service").value("asset-web"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void reportsServiceUnavailableWhenHealthIsNotUp() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(Health.down().build().getStatus());
        when(endpoint.health()).thenReturn(descriptor);
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthEndpoint> health = mock(ObjectProvider.class);
        when(health.getIfAvailable()).thenReturn(endpoint);

        var result = new HealthController(health).health();

        // A non-UP status must not be reported as HTTP 200.
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().data()).containsEntry("status", "DOWN");
    }
}
