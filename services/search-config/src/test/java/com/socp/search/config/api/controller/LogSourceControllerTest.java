package com.socp.search.config.api.controller;

import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.auth.security.AuthInterceptor;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.error.api.PageResponse;
import com.socp.search.config.config.IngestLimitsProperties;
import com.socp.search.config.config.VectorProperties;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.entity.LogSourceEntity;
import com.socp.search.config.persistence.repository.LogSourceRepository;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.search.config.service.IngestPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogSourceControllerTest {

    @Test
    void rawHttpLimitRejectsBeforePipelineAndUsesConfiguredSearchBudget() throws Exception {
        IngestPipeline pipeline = mock(IngestPipeline.class);
        var controller = new LogSourceController(new LogSourceStore(mock(LogSourceRepository.class)),
                mock(SinkTargetStore.class), pipeline, new IngestLimitsProperties(), vectorProperties());
        var advice = new com.socp.platform.auth.security.IngestBodyLimitAdvice(
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("socp.ingest.limits.max-body-bytes", "32"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice).build();
        for (String type : List.of("application/json", "application/x-ndjson", "text/plain")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/ingest").contentType(type).content("x".repeat(33)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isPayloadTooLarge());
        }
        org.mockito.Mockito.verifyNoInteractions(pipeline);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        AuthenticatedIdentityContext.clear();
    }

    @Test
    void seedUsesDefaultTenantWithoutLeakingContext() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenReturn(List.of());
        LogSourceController controller = controller(repository);

        controller.seed();

        verify(repository, atLeastOnce()).findByTenantId("default");
        ArgumentCaptor<LogSourceEntity> saved = ArgumentCaptor.forClass(LogSourceEntity.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        saved.getAllValues().forEach(entity -> assertEquals("default", entity.getTenantId()));
        assertNull(TenantContext.get());
    }

    @Test
    void productionBootDoesNotReadOrCreateDemoSources() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        var environment = new org.springframework.mock.env.MockEnvironment();
        environment.setActiveProfiles("prod");
        TenantContext.set("operator-tenant");
        var controller = new LogSourceController(new LogSourceStore(repository),
                mock(SinkTargetStore.class), mock(IngestPipeline.class),
                new IngestLimitsProperties(), vectorProperties(), environment);

        controller.seed();

        org.mockito.Mockito.verifyNoInteractions(repository);
        assertEquals("operator-tenant", TenantContext.get());
    }

    @Test
    void seedIsIdempotentOnRestart() {
        TenantContext.set("default");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        LogSource existing = LogSource.create("real-file", SourceType.FILE, ParseFormat.AUTO,
                "demo/sample.log", null, null, "local", true);
        LogSourceEntity entity = entity(existing);
        when(repository.findByTenantId("default")).thenReturn(List.of(entity));
        when(repository.findByTenantIdAndSourceId("default", existing.id())).thenReturn(Optional.of(entity));
        LogSourceController controller = controller(repository);

        controller.seed();

        ArgumentCaptor<LogSourceEntity> saved = ArgumentCaptor.forClass(LogSourceEntity.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        // The catalogue is not empty on restart, so only the genuinely missing seed is written.
        assertEquals(1, saved.getAllValues().size(), "已存在的种子不得重复写入");
        assertEquals("real-syslog", saved.getAllValues().getFirst().getName());
    }

    @Test
    void seedNeverPersistsTheCollectorCredential() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenReturn(List.of());
        SinkTargetStore sinks = mock(SinkTargetStore.class);
        LogSourceController controller = new LogSourceController(new LogSourceStore(repository), sinks,
                mock(IngestPipeline.class), new IngestLimitsProperties(), vectorProperties());

        controller.seed();

        verify(sinks, org.mockito.Mockito.never()).save(any(SinkTarget.class));
    }

    @Test
    void seedRestoresExistingTenantContext() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenReturn(List.of());
        LogSourceController controller = controller(repository);
        TenantContext.set("tenant-a");

        controller.seed();

        assertEquals("tenant-a", TenantContext.get());
    }

    @Test
    void seedRestoresExistingTenantContextWhenPersistenceFails() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenThrow(new IllegalStateException("db unavailable"));
        LogSourceController controller = controller(repository);
        TenantContext.set("tenant-a");

        assertThrows(IllegalStateException.class, controller::seed);

        assertEquals("tenant-a", TenantContext.get());
    }

    @Test
    void pagedCatalogueUsesDatabasePageAndSharedEnvelope() {
        TenantContext.set("tenant-a");
        LogSourceEntity entity = new LogSourceEntity();
        entity.setTenantId("tenant-a");
        entity.setStorageId("storage-source");
        entity.setId("source");
        entity.setName("source");
        entity.setType("FILE");
        entity.setFormat("AUTO");
        entity.setPath("/var/log/auth.log");
        entity.setEnabled(true);
        LogSourceRepository repository = mock(LogSourceRepository.class);
        Pageable ordered = org.springframework.data.domain.PageRequest.of(0, 1,
                org.springframework.data.domain.Sort.by("sourceId").ascending());
        when(repository.findByTenantId("tenant-a", ordered))
                .thenReturn(new PageImpl<>(List.of(entity), ordered, 2));
        LogSourceController controller = controller(repository);

        @SuppressWarnings("unchecked")
        PageResponse<LogSource> result =
                (PageResponse<LogSource>) controller.list(1, 1).data();

        assertEquals(1, result.items().size());
        assertEquals(2, result.total());
        assertEquals(1, result.page());
        assertEquals(1, result.size());
    }

    @Test
    void pagedNameSearchIsTenantScopedOrderedAndBounded() {
        TenantContext.set("tenant-a");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        Pageable ordered = org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("sourceId").ascending());
        when(repository.findByTenantIdAndNameContainingIgnoreCase("tenant-a", "Auth", ordered))
                .thenReturn(new PageImpl<>(List.of(), ordered, 0));
        LogSourceController controller = controller(repository);

        @SuppressWarnings("unchecked")
        PageResponse<LogSource> result = (PageResponse<LogSource>) controller.list(1, 10, " Auth ").data();

        assertEquals(0, result.total());
        verify(repository).findByTenantIdAndNameContainingIgnoreCase("tenant-a", "Auth", ordered);
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.list(1, 10, "x".repeat(129)));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void sourceSearchHttpRouteKeepsThePagedEnvelope() throws Exception {
        TenantContext.set("tenant-a");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        Pageable ordered = org.springframework.data.domain.PageRequest.of(0, 20,
                org.springframework.data.domain.Sort.by("sourceId").ascending());
        when(repository.findByTenantIdAndNameContainingIgnoreCase("tenant-a", "Deep", ordered))
                .thenReturn(new PageImpl<>(List.of(), ordered, 501));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller(repository)).build();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sources").param("page", "1").param("size", "20")
                        .param("q", "Deep"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.total").value(501))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.page").value(1));
        verify(repository).findByTenantIdAndNameContainingIgnoreCase("tenant-a", "Deep", ordered);
    }

    @Test
    void missingSourceReadsFailClosedOnTheSingleErrorChannel() {
        TenantContext.set("tenant-a");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantIdAndSourceId("tenant-a", "missing")).thenReturn(Optional.empty());
        LogSourceController controller = controller(repository);

        ApiException read = assertThrows(ApiException.class, () -> controller.get("missing"));
        assertEquals(404, read.getCode());

        ApiException updated = assertThrows(ApiException.class,
                () -> controller.update("missing", request()));
        assertEquals(404, updated.getCode());

        ApiException rendered = assertThrows(ApiException.class,
                () -> controller.vectorConfig("missing", false));
        assertEquals(404, rendered.getCode());
    }

    @Test
    void vectorConfigRedactsTheCollectorCredentialByDefault() {
        TenantContext.set("tenant-a");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        LogSource source = LogSource.create("auth-log", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/auth.log", null, null, "prod", true);
        when(repository.findByTenantIdAndSourceId("tenant-a", source.id()))
                .thenReturn(Optional.of(entity(source)));
        SinkTargetStore sinks = mock(SinkTargetStore.class);
        when(sinks.resolveForRendering(null)).thenReturn(new SinkTarget("platform",
                "平台 SEARCH ingest", "GLS_INGEST", "http://search:18081/ingest", null, true, Instant.now()));
        LogSourceController controller = new LogSourceController(new LogSourceStore(repository), sinks,
                mock(IngestPipeline.class), new IngestLimitsProperties(), vectorProperties());

        String redacted = controller.vectorConfig(source.id(), false);
        assertTrue(redacted.contains("<SOCP_INGEST_TOKEN>"), "默认渲染必须是占位符");
        assertFalse(redacted.contains("prod-vector-token"), "默认渲染不得含明文 token");

        // Only an admin may ask for the secret, and only through the explicit parameter.
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity(
                "alice", "tenant-a", "analyst", java.util.Set.of(), java.util.Set.of(),
                AuthenticatedIdentity.Kind.USER));
        ApiException denied = assertThrows(ApiException.class,
                () -> controller.vectorConfig(source.id(), true));
        assertEquals(403, denied.getCode());

        AuthenticatedIdentityContext.set(new AuthenticatedIdentity(
                "root", "tenant-a", "admin", java.util.Set.of(), java.util.Set.of(),
                AuthenticatedIdentity.Kind.USER));
        String revealed = controller.vectorConfig(source.id(), true);
        assertTrue(revealed.contains("Bearer prod-vector-token"));
    }

    @Test
    void vectorConfigEndpointRejectsReadOnlyRoleAndRequiresRenderParity() throws Exception {
        JwtValidator validator = mock(JwtValidator.class);
        when(validator.isDevBypass()).thenReturn(true);
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setDevBypass(true);
        AuthInterceptor interceptor = new AuthInterceptor(validator, properties);

        assertRejectedByRole(interceptor, "vectorConfig", "viewer");
        assertRejectedByRole(interceptor, "renderAll", "viewer");
        assertAllowedByRole(interceptor, "vectorConfig", "analyst");
        assertAllowedByRole(interceptor, "renderAll", "admin");
    }

    private void assertRejectedByRole(AuthInterceptor interceptor, String methodName, String role)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + role + "-token");
        request.addHeader("X-Role", role);
        request.setRequestURI("/search-config/api/v1/sources/any/vector-config");

        ApiException denied = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler(methodName)),
                role + " 角色不得读取渲染配置");
        assertEquals(403, denied.getCode());
    }

    private void assertAllowedByRole(AuthInterceptor interceptor, String methodName, String role)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + role + "-token");
        request.addHeader("X-Role", role);
        request.setMethod("POST");
        request.setRequestURI("/search-config/api/v1/render");
        TenantContext.set("tenant-a");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler(methodName)),
                role + " 角色应可读取渲染配置");
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        LogSourceController controller = controller(mock(LogSourceRepository.class));
        Method method = java.util.Arrays.stream(LogSourceController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        return new HandlerMethod(controller, method);
    }

    private static VectorProperties vectorProperties() {
        VectorProperties properties = new VectorProperties();
        properties.setToken("prod-vector-token");
        properties.setUri("http://search:18081/ingest");
        return properties;
    }

    private static LogSourceEntity entity(LogSource source) {
        LogSourceEntity entity = new LogSourceEntity();
        entity.setStorageId(source.id());
        entity.setId(source.id());
        entity.setTenantId("default");
        entity.setName(source.name());
        entity.setType(source.type().name());
        entity.setFormat(source.format().name());
        entity.setPath(source.path());
        entity.setAddress(source.address());
        entity.setEnabled(source.enabled());
        return entity;
    }

    private static com.socp.search.config.api.request.LogSourceRequest request() {
        return new com.socp.search.config.api.request.LogSourceRequest(
                "auth", SourceType.FILE, ParseFormat.AUTO, "/var/log/auth.log", null,
                null, "prod", true, "beginning", null, null, List.of(), null,
                null, "utf-8", "event_time", "UTC", List.of(), 1, null, null);
    }

    private LogSourceController controller(LogSourceRepository repository) {
        return new LogSourceController(
                new LogSourceStore(repository),
                mock(SinkTargetStore.class),
                mock(IngestPipeline.class),
                new IngestLimitsProperties(),
                vectorProperties());
    }
}
