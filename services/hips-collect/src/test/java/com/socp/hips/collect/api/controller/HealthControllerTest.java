package com.socp.hips.collect.api.controller;

import com.socp.hips.collect.api.request.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一响应体契约：{code:0, message:"ok", data:{...}}（com.socp.platform.error.api.ApiResult）。
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
                .andExpect(jsonPath("$.data.service").value("hips-collect"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
