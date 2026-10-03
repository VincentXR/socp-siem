package com.socp.incident.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socp.incident.web.IncidentWebApplication;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.persistence.repository.CaseMutationRepository;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.incident.web.service.CaseService;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.http.ExternalEndpointPolicy;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.ServiceRequestSigner;
import com.socp.platform.client.http.ServiceTokenProvider;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Actual client, HTTP routing/validation/auth, case transaction and durable replay receipt. */
@SpringBootTest(classes = IncidentWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:incident-note-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
                "spring.datasource.password=", "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
                "socp.security.dev-bypass=false", "socp.security.jwt-secret=" + IncidentNoteHttpContractTest.JWT_SECRET,
                "socp.security.service-secret=" + IncidentNoteHttpContractTest.SERVICE_SECRET,
                "socp.security.audience=socp-api", "socp.security.issuer-uri=", "socp.security.jwk-set-uri=",
                "socp.audit.sink=memory", "socp.ratelimit.backend=memory",
                "spring.autoconfigure.exclude=com.socp.platform.client.config.SocpClientAutoConfiguration"
        })
class IncidentNoteHttpContractTest {
    static final String JWT_SECRET = "incident-note-http-test-jwt-secret-at-least-32-bytes";
    static final String SERVICE_SECRET = "incident-note-http-test-service-secret-at-least-32-bytes";
    @LocalServerPort int port;
    @Autowired CaseStore store;
    @Autowired CaseService legacyCases;
    @Autowired CaseMutationRepository receipts;
    @Autowired ObjectMapper json;
    @Autowired AuditSink audit;
    private String tenant;
    private IncidentClient client;

    @BeforeEach
    void prepare() throws Exception {
        tenant = "note-http-" + UUID.randomUUID();
        TenantContext.set(tenant);
        client = client("service:ai-assistant", "service-root", "ai-assistant", SERVICE_SECRET);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void investigationSummaryUsesJsonAndCommitsOneReplaySafeNoteWithItsReceipt() throws Exception {
        Case incident = store.save(Case.create("AI investigation", "host-1", "HIGH"));
        // The real investigation composer permits 8,000 characters. A query-string
        // request exceeds normal request-line limits even before Unicode encoding.
        String content = "SOCP AI investigation summary\n\"Evidence\" & path\\file\t中文\n" + "证据".repeat(3600);
        String key = "investigation-" + UUID.randomUUID();
        JsonNode first = accepted(client.addNote(incident.id(), "ai-investigation", content, key));
        JsonNode replay = accepted(client.addNote(incident.id(), "ai-investigation", content, key));

        assertThat(first.path("case").path("id").asText()).isEqualTo(incident.id());
        assertThat(first.path("changed").asBoolean()).isTrue();
        assertThat(replay.path("duplicate").asBoolean()).isTrue();
        assertThat(replay.path("case").path("rowVersion").asLong())
                .isEqualTo(first.path("case").path("rowVersion").asLong());
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(tenant, incident.id(), key)).isPresent();
        assertThat(store.timeline(incident.id(), 0, 20).getContent()).singleElement().satisfies(note -> {
            assertThat(note.getType()).isEqualTo("NOTE");
            assertThat(note.getMessage()).isEqualTo("ai-investigation: " + content);
        });
        assertThat(audit.recent(tenant, 10, "ADD_INCIDENT_NOTE"))
                .allSatisfy(record -> assertThat(record.operator()).isEqualTo("service:ai-assistant"));

        assertThat(client.addNote(incident.id(), "ai-investigation", "changed evidence", key).status()).isEqualTo(409);
        assertThat(store.timeline(incident.id(), 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void concurrentReplaysCommitExactlyOneNoteAndReturnTheSameCase() throws Exception {
        Case incident = store.save(Case.create("Concurrent note", "host-2", "HIGH"));
        String key = "concurrent-investigation";
        CyclicBarrier start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<JsonNode> append = () -> {
                try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
                    start.await(10, TimeUnit.SECONDS);
                    return accepted(client.addNote(incident.id(), "ai-investigation", "Same summary", key));
                }
            };
            var first = workers.submit(append);
            var second = workers.submit(append);
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(response -> response.path("duplicate").asBoolean())
                    .containsExactlyInAnyOrder(false, true);
            assertThat(responses).allSatisfy(response ->
                    assertThat(response.path("case").path("id").asText()).isEqualTo(incident.id()));
        }
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(tenant, incident.id(), key)).isPresent();
        assertThat(store.timeline(incident.id(), 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void upgradeAdoptsTheActualLegacyNoteWithoutDuplicatingHistoryOrChangingItsVersion() throws Exception {
        Case incident = store.save(Case.create("Legacy accepted note", "host-upgrade", "HIGH"));
        String key = "investigation-before-upgrade";
        String content = "SOCP AI investigation summary\nEvidence from before the upgrade";
        legacyCases.addNote(incident.id(), "ai-investigation", content, key);
        long version = store.getMetadata(incident.id()).rowVersion();
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(tenant, incident.id(), key)).isEmpty();

        // The first replay must not adopt a conflicting command into the new ledger.
        assertThat(client.addNote(incident.id(), "ai-investigation", "Different evidence", key).status()).isEqualTo(409);
        assertThat(client.addNote(incident.id(), "another-delegate", content, key).status()).isEqualTo(409);
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(tenant, incident.id(), key)).isEmpty();
        JsonNode adopted = accepted(client.addNote(incident.id(), "ai-investigation", content, key));
        assertThat(adopted.path("duplicate").asBoolean()).isTrue();
        assertThat(adopted.path("changed").asBoolean()).isFalse();
        assertThat(adopted.path("case").path("rowVersion").asLong()).isEqualTo(version);
        assertThat(accepted(client.addNote(incident.id(), "ai-investigation", content, key))
                .path("duplicate").asBoolean()).isTrue();
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(tenant, incident.id(), key)).isPresent();
        assertThat(store.timeline(incident.id(), 0, 20).getContent()).singleElement().satisfies(note -> {
            assertThat(note.getEventKey()).isEqualTo("note:" + key);
            assertThat(note.getMessage()).isEqualTo("ai-investigation: " + content);
        });
        assertThat(store.getMetadata(incident.id()).rowVersion()).isEqualTo(version);
    }

    @Test
    void signedServiceDelegationCannotReachAnotherTenantsCase() {
        Case incident = store.save(Case.create("Tenant-owned case", "host-3", "HIGH"));
        try (TenantContext.Scope ignored = TenantContext.open("other-tenant")) {
            assertThat(client.addNote(incident.id(), "ai-investigation", "Summary", "foreign-note").status()).isEqualTo(404);
            assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey("other-tenant", incident.id(), "foreign-note")).isEmpty();
        }
        assertThat(store.timeline(incident.id(), 0, 20).getTotalElements()).isZero();
    }

    @Test
    void serviceJwtNeedsValidMatchingSignedDelegationBeforeWriting() throws Exception {
        Case incident = store.save(Case.create("Authenticated case", "host-4", "HIGH"));
        assertThat(client("service:ai-assistant", "service-root", "ai-assistant", "")
                .addNote(incident.id(), "ai-investigation", "Summary", "unsigned").status()).isEqualTo(403);
        assertThat(client("service:ai-assistant", "service-root", "soar-web", SERVICE_SECRET)
                .addNote(incident.id(), "ai-investigation", "Summary", "wrong-service").status()).isEqualTo(401);
        assertThat(store.timeline(incident.id(), 0, 20).getTotalElements()).isZero();
    }

    @Test
    void humanAuthorAndTenantAreTakenFromTheJwtAndUnkeyedCallsStillWork() throws Exception {
        Case incident = store.save(Case.create("Human note", "host-5", "HIGH"));
        IncidentClient human = client("analyst-9", tenant, "", "");
        try (TenantContext.Scope ignored = TenantContext.open("untrusted-header-tenant")) {
            accepted(human.addNote(incident.id(), "forged-author", "Reviewed evidence"));
        }
        assertThat(store.timeline(incident.id(), 0, 20).getContent()).singleElement().satisfies(note ->
                assertThat(note.getMessage()).isEqualTo("analyst-9: Reviewed evidence"));
        assertThat(audit.recent(tenant, 10, "ADD_INCIDENT_NOTE"))
                .singleElement().satisfies(record -> assertThat(record.operator()).isEqualTo("analyst-9"));
    }

    private JsonNode accepted(ServiceCall response) throws Exception {
        assertThat(response.status()).as("HTTP response: %s", response.body()).isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asInt()).isZero();
        return envelope.path("data");
    }

    private IncidentClient client(String subject, String jwtTenant, String service, String secret) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject(subject).claim("tenant", jwtTenant).claim("role", "analyst").audience("socp-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
        when(tokens.token()).thenReturn(jwt.serialize());
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("loopback",
                Map.of("socp.incident.url", "http://localhost:" + port)));
        SocpClientProperties properties = new SocpClientProperties();
        properties.setRequestTimeoutMs(10_000);
        return new IncidentClient(new SocpHttpClient(new ServiceEndpoints(environment), tokens, properties,
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class),
                new ServiceRequestSigner(service, secret), new ExternalEndpointPolicy(properties)));
    }
}
