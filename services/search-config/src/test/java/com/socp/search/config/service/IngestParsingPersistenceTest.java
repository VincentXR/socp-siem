package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.service.DetectClient;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.repository.IngestParseFailureRepository;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import com.socp.search.config.persistence.store.SearchStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real parsing, normalization, event/outbox storage, quarantine, and replay boundaries. */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IngestParsingPersistenceTest {
    @Autowired SearchEventRepository events;
    @Autowired IngestionOutboxRepository outbox;
    @Autowired IngestParseFailureRepository failures;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private IngestPipeline pipeline;
    private IngestParseFailureService quarantine;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepare() {
        TenantContext.set("parser-contract");
        transaction = new TransactionTemplate(transactions);
        var normalizer = new IngestEventNormalizer(null, new ParserRegistry(), null, null,
                com.socp.rule.time.SourceEventTimePolicy.defaults(), java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-30T00:00:00Z"), java.time.ZoneOffset.UTC));
        var commits = new IngestionCommitService(events, outbox, mock(SearchStore.class));
        var monitor = mock(IngestTaskMonitor.class);
        when(monitor.runtime(any(), anyBoolean())).thenReturn(Map.of("eps1m", 0.0));
        var properties = new IngestRuntimeProperties();
        quarantine = new IngestParseFailureService(failures, normalizer, commits, jdbc, properties);
        pipeline = new IngestPipeline(normalizer, commits, monitor, mock(DetectClient.class),
                new SimpleMeterRegistry(), properties, quarantine);
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("t_ingest_parse_failure", "t_ingestion_outbox", "t_search_event")) {
            jdbc.update("delete from " + table + " where tenant_id = ?", "parser-contract");
        }
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"falco", "sysmon", "auditd"})
    void vendorProducerIdentityMakesRetriedHttpBatchesIdempotentWithoutATransportKey(String vendor) {
        String vendorFields = switch (vendor) {
            case "falco" -> "\"rule\":\"Shell\",\"priority\":\"Warning\",\"output\":\"shell started\"";
            case "sysmon" -> "\"Event\":{\"EventID\":1,\"EventData\":{\"Image\":\"cmd.exe\"}}";
            case "auditd" -> "\"type\":\"EXECVE\",\"exe\":\"/bin/bash\"";
            default -> throw new AssertionError(vendor);
        };
        String raw = "{\"eventId\":\"vendor-event\"," + vendorFields + "}";

        var first = transaction.execute(status -> pipeline.process(raw, "vector"));
        var retry = transaction.execute(status -> pipeline.process(raw, "vector"));

        assertEquals(1, first.get("created"));
        assertEquals(0, retry.get("created"));
        assertEquals(1, retry.get("duplicates"));
        assertEquals(1, events.findByTenantIdAndEventIdIn("parser-contract", List.of("vendor-event")).size());
        assertEquals(1, outbox.findByTenantIdAndEventIdIn("parser-contract", List.of("vendor-event")).size());
    }

    @Test
    void vectorAutoEnvelopePublishesCanonicalNestedFieldsAndStableIdentity() throws Exception {
        var mapper = new ObjectMapper();
        String inner = mapper.writeValueAsString(Map.of("eventId", "inner-event", "source", "auth",
                "host", "auth-host", "src_ip", "203.0.113.9", "action", "login_failed",
                "message", "Failed password", "timestamp", "2026-09-20T00:00:00Z"));
        String envelope = mapper.writeValueAsString(Map.of("eventId", "vector-envelope", "message", inner,
                "source_id", "configured-source", "parse_format", "auto"));

        transaction.execute(status -> pipeline.process(envelope, "vector"));
        var rows = outbox.findByTenantIdAndEventIdIn("parser-contract", List.of("inner-event"));
        assertEquals(1, rows.size());
        var canonical = mapper.readTree(rows.getFirst().getPayload());
        assertEquals("auth", canonical.get("source").asText());
        assertEquals("auth-host", canonical.get("host").asText());
        assertEquals("203.0.113.9", canonical.at("/fields/src_ip").asText());
        assertEquals("login_failed", canonical.at("/fields/action").asText());
        assertEquals("configured-source", canonical.at("/fields/source_id").asText());
        assertEquals("parser-contract", canonical.at("/fields/tenant_id").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cef", "leef1", "leef2", "syslog3164", "syslog5424"})
    void standardSecurityLogsReachDurableCanonicalOutboxAndReplayExactlyOnce(String format) throws Exception {
        String raw = switch (format) {
            case "cef" -> "CEF:0|ArcSight|Logger|1|100|denied|10|src=192.0.2.1 msg=connection denied by policy dpt=80";
            case "leef1" -> "LEEF:1.0|IBM|IPS|1|100|src=192.0.2.1\tsev=10\tmsg=connection denied by policy\tdstPort=80";
            case "leef2" -> "LEEF:2.0|IBM|IPS|1|100|0x5E|src=192.0.2.1^sev=10^msg=connection denied by policy^dstPort=80";
            case "syslog3164" -> "<165>Oct 11 22:14:15 host app[123]: connection denied by policy";
            case "syslog5424" -> "<165>1 2026-10-11T22:14:15Z host app 123 ID47 [example@32473 key=\"value\"] connection denied by policy";
            default -> throw new AssertionError(format);
        };
        var mapper = new ObjectMapper();
        String id = "standard-" + format;
        String envelope = mapper.writeValueAsString(Map.of("eventId", id, "message", raw));
        var first = transaction.execute(status -> pipeline.process(envelope, "vector"));
        var replay = transaction.execute(status -> pipeline.process(envelope, "vector"));
        assertEquals(1, first.get("created"));
        assertEquals(1, replay.get("duplicates"));
        assertEquals(0, failures.countByTenantId("parser-contract"));
        assertEquals(1, events.findByTenantIdAndEventIdIn("parser-contract", List.of(id)).size());
        var rows = outbox.findByTenantIdAndEventIdIn("parser-contract", List.of(id));
        assertEquals(1, rows.size());
        var payload = mapper.readTree(rows.getFirst().getPayload());
        assertEquals("connection denied by policy", payload.path("msg").asText());
        assertEquals("parser-contract", payload.path("tenantId").asText());
        if (format.startsWith("syslog")) {
            assertEquals("MEDIUM", payload.path("severity").asText());
            assertEquals("2026-10-11T22:14:15Z", payload.path("timestamp").asText());
            assertFalse(payload.path("fields").has("event_time_generated"));
            assertEquals("123", payload.path("ecs").path("process.pid").asText());
        } else {
            assertEquals("CRITICAL", payload.path("severity").asText());
            assertEquals("192.0.2.1", payload.path("ecs").path("source.ip").asText());
            assertEquals("80", payload.path("ecs").path("destination.port").asText());
        }
    }

    @Test
    void realMalformedJsonIsDurablyQuarantinedAndReplayRemainsPending() {
        String body = "{broken\nordinary free text";
        var result = transaction.execute(status -> pipeline.process(body, "vector", "batch-1"));
        assertEquals(1, result.get("accepted"));
        assertEquals(1, result.get("parseFailed"));
        assertEquals(1, result.get("quarantined"));
        assertEquals(2, result.get("acknowledged"));
        var stored = failures.findByTenantIdOrderByReceivedAtDescIdAsc("parser-contract", PageRequest.of(0, 10));
        assertEquals(1, stored.getTotalElements());
        var row = stored.getContent().getFirst();
        assertEquals("{broken", row.getRawPayload());
        assertTrue(row.getFailureReason().contains("JSON"));

        transaction.execute(status -> pipeline.process(body, "vector", "batch-1"));
        assertEquals(1, failures.countByTenantId("parser-contract"));
        var replay = transaction.execute(status -> quarantine.replay(row.getId()));
        assertEquals(false, replay.get("replayed"));
        assertEquals("PENDING", replay.get("replayStatus"));
        assertEquals(1, jdbc.queryForObject("select count(*) from t_ingestion_outbox where tenant_id = ?",
                Integer.class, "parser-contract"));
    }

    @Test
    void quarantineFailureRollsBackMixedBatchAndCannotReturnAnAcknowledgement() {
        var unavailable = mock(IngestParseFailureService.class);
        when(unavailable.record(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        var normalizer = new IngestEventNormalizer(null, null, null, new ParserRegistry());
        var monitor = mock(IngestTaskMonitor.class);
        when(monitor.runtime(any(), anyBoolean())).thenReturn(Map.of("eps1m", 0.0));
        var boundary = new IngestPipeline(normalizer,
                new IngestionCommitService(events, outbox, mock(SearchStore.class)), monitor,
                mock(DetectClient.class), new SimpleMeterRegistry(), new IngestRuntimeProperties(), unavailable);
        String body = String.join("\n", java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "{\"eventId\":\"before-failure-" + i + "\",\"message\":\"text\"}").toList()) + "\n{broken";

        ApiException failure = assertThrows(ApiException.class,
                () -> transaction.execute(status -> boundary.process(body, "vector", "batch-2")));

        assertEquals(503, failure.getCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from t_ingestion_outbox where tenant_id = ?",
                Integer.class, "parser-contract"));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_search_event where tenant_id = ?",
                Integer.class, "parser-contract"));
    }
}
