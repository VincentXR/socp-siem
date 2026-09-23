package com.socp.alert.api.controller;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.alert.domain.AlarmTechniqueCount;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.service.AlarmStatisticsService;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.auth.security.AuthInterceptor;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.error.web.GlobalExceptionHandler;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AlarmTechniqueControllerTest {
    private final AlarmRepository repository = mock(AlarmRepository.class);
    private final JwtValidator validator = mock(JwtValidator.class);
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = standaloneSetup(new AlarmTechniqueController(new AlarmStatisticsService(repository)))
                .addInterceptors(new AuthInterceptor(validator, new SocpSecurityProperties()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clear() { TenantContext.clear(); AuthenticatedIdentityContext.clear(); }
    private void identity(String role) {
        var claims = new JWTClaimsSet.Builder().subject("user").claim("role", role).claim("tenant", "tenant-a").build();
        when(validator.validate("token")).thenReturn(claims);
        when(validator.extractTenant(claims)).thenReturn("tenant-a");
    }
    @ParameterizedTest @ValueSource(strings = {"admin", "analyst", "viewer"})
    void countsUseValidatedTenantDespiteSpoofedHeader(String role) throws Exception {
        identity(role);
        when(repository.countByTechniqueInWindow(eq("tenant-a"), eq(List.of("T1110")), any(), any()))
                .thenReturn(List.of(new AlarmTechniqueCount("T1110", 151L)));
        mvc.perform(post("/api/alarms/technique-counts").header("Authorization", "Bearer token")
                        .header("X-Tenant-Id", "other").contentType("application/json").content("{\"techniqueIds\":[\"T1110\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.counts.T1110").value(151))
                .andExpect(jsonPath("$.data.from").isString()).andExpect(jsonPath("$.data.until").isString());
        verify(repository).countByTechniqueInWindow(eq("tenant-a"), eq(List.of("T1110")), any(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"approver", "unknown"})
    void unapprovedRoleCannotQuery(String role) throws Exception {
        identity(role);
        mvc.perform(post("/api/v1/alarms/technique-counts").header("Authorization", "Bearer token")
                        .header("X-Role", "admin").contentType("application/json").content("{\"techniqueIds\":[\"T1110\"]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }
    @Test void rejectsOversizedAndEmptyRequestsBeforeQuery() throws Exception {
        identity("viewer");
        for (var ids : List.of(List.of(), java.util.Collections.nCopies(101, "T1110"), List.of(" "), List.of("X".repeat(33)))) {
            String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("techniqueIds", ids));
            mvc.perform(post("/api/alarms/technique-counts").header("Authorization", "Bearer token")
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(repository);
    }
}
