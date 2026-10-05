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
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
        var normalizer = new IngestEventNormalizer(null, null, null, new ParserRegistry());
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
