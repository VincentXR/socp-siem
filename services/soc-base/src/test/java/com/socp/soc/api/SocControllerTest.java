package com.socp.soc.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soc.model.TenantInfo;
import com.socp.soc.store.TenantStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SOC 租户 API 的 Web 层切片测试：TenantStore 被 mock，只验证路由 / 序列化 / 状态码。
 *
 * <p>约定：
 * <ul>
 *   <li>addFilters=false 隔离掉 servlet 过滤器（租户注入、traceId、后续可能加入的安全过滤链）；</li>
 *   <li>socp-auth 的 AuthInterceptor 是 HandlerInterceptor，@WebMvcTest 会加载它，
 *       因此业务路径必须带 Bearer 令牌，否则被判 401。</li>
 * </ul>
 */
@WebMvcTest(SocController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class SocControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private TenantStore store;

    @Test
    void listTenantsReturnsJsonArray() throws Exception {
        given(store.list()).willReturn(List.of(
                TenantInfo.create("默认租户", "default"),
                TenantInfo.create("安全运营团队", "soc-team")));

        mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("default"))
                .andExpect(jsonPath("$[1].name").value("安全运营团队"))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    void createTenantPersistsAndEchoesBody() throws Exception {
        given(store.save(any(TenantInfo.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/tenants")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "红队", "code", "red-team"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("红队"))
                .andExpect(jsonPath("$.code").value("red-team"))
                .andExpect(jsonPath("$.userCount").value(0))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void overviewCountsTenantsAndListsServices() throws Exception {
        given(store.list()).willReturn(List.of(TenantInfo.create("默认租户", "default")));

        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").value(1))
                .andExpect(jsonPath("$.platform").value("SOCP v1.0"))
                .andExpect(jsonPath("$.services.length()").value(greaterThan(0)));
    }
}
