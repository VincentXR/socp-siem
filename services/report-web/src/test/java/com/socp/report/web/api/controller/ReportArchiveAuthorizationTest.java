package com.socp.report.web.api.controller;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.auth.security.AuthInterceptor;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.error.web.GlobalExceptionHandler;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.report.web.persistence.store.ReportObjectStore;
import com.socp.report.web.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReportArchiveAuthorizationTest {
    private static final String PATH = "/api/v1/reports/archive/download";
    private static final String KEY = "reports/tenant-report/20260923/daily.json";
    private final JwtValidator validator = mock(JwtValidator.class);
    private final ReportObjectStore store = mock(ReportObjectStore.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ReportController(mock(ReportService.class), store))
                .addInterceptors(new AuthInterceptor(validator, new SocpSecurityProperties()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(store.presignedGet(KEY)).thenReturn("https://storage.example/signed");
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuthenticatedIdentityContext.clear();
    }

    private void authenticate(String role) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("report-user")
                .claim("tenant", "tenant-report").claim("role", role).build();
        when(validator.validate("signed-token")).thenReturn(claims);
        when(validator.extractTenant(claims)).thenReturn("tenant-report");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "analyst", "viewer"})
    void archiveReadersCanDownloadTheirTenantFiles(String role) throws Exception {
        authenticate(role);
        mvc.perform(get(PATH).header("Authorization", "Bearer signed-token").param("key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.key").value(KEY))
                .andExpect(jsonPath("$.data.url").value("https://storage.example/signed"));
        verify(store).presignedGet(KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"approver", "unknown", "ingest"})
    void rolesWithoutArchiveReadAccessCannotObtainSignedUrls(String role) throws Exception {
        authenticate(role);
        mvc.perform(get(PATH).header("Authorization", "Bearer signed-token")
                        .header("X-Role", "admin").param("key", KEY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(store);
    }

    @Test
    void permittedRoleCannotSelectAnotherTenantWithHeadersOrObjectKey() throws Exception {
        authenticate("admin");
        mvc.perform(get(PATH).header("Authorization", "Bearer signed-token")
                        .header("X-Tenant-Id", "other").param("key", "reports/other/20260923/daily.json"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void missingAuthenticationCannotIssueSignedUrls() throws Exception {
        mvc.perform(get(PATH).param("key", KEY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
}
