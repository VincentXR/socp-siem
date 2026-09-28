package com.socp.search.config.service;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.persistence.entity.IngestParseFailureEntity;
import com.socp.search.config.persistence.repository.IngestParseFailureRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded durable quarantine and idempotent replay for unparseable raw input. */
@Service
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class IngestParseFailureService {
    private final IngestParseFailureRepository repository;
    private final IngestEventNormalizer normalizer;
    private final IngestionCommitService commits;
    private final JdbcTemplate jdbc;
    private final int maxRowsPerTenant;
    private final boolean postgres;

    public IngestParseFailureService(IngestParseFailureRepository repository,
                                     IngestEventNormalizer normalizer,
                                     IngestionCommitService commits,
                                     JdbcTemplate jdbc,
                                     IngestRuntimeProperties properties) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.commits = commits;
        this.jdbc = jdbc;
        this.maxRowsPerTenant = Math.max(1, properties.getQuarantine().getMaxRowsPerTenant());
        String product = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        this.postgres = "PostgreSQL".equals(product);
    }

    @Transactional
    public IngestParseFailureEntity record(String raw, String collector, String parserVersion,
                                           String reason, String requestIdentity) {
        String tenant = TenantContext.require();
        String failureKey = requestIdentity == null || requestIdentity.isBlank()
                ? UUID.randomUUID().toString() : digest(requestIdentity);
        var existing = repository.findByTenantIdAndFailureKey(tenant, failureKey);
        if (existing.isPresent()) return existing.get();

        // Serializes only admission for this tenant. It keeps the configured
        // cap meaningful across API replicas without process-local locks.
        if (postgres) {
            jdbc.query("select pg_advisory_xact_lock(hashtext(?))", result -> { },
                    "ingest-quarantine:" + tenant);
            existing = repository.findByTenantIdAndFailureKey(tenant, failureKey);
            if (existing.isPresent()) return existing.get();
        }
        if (repository.countByTenantId(tenant) >= maxRowsPerTenant) {
            throw ApiException.of(507, "Ingest parse-failure quarantine is full for this tenant");
        }

        IngestParseFailureEntity row = new IngestParseFailureEntity();
        row.setId(UUID.nameUUIDFromBytes((tenant + "\u0000" + failureKey)
                .getBytes(StandardCharsets.UTF_8)).toString());
        row.setFailureKey(failureKey);
        row.setCollectorId(normalize(collector, "unknown", 255));
        row.setRawPayload(raw);
        row.setReceivedAt(Instant.now());
        row.setParserVersion(normalize(parserVersion, "unknown", 255));
        row.setFailureReason(normalize(reason, "parse failed", 1024));
        row.setReplayStatus("PENDING");
        row.setReplayAttempts(0);
        return repository.saveAndFlush(row);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> page(int page, int size) {
        return repository.findByTenantIdOrderByReceivedAtDescIdAsc(
                TenantContext.require(), PageRequest.of(page - 1, size)).map(IngestParseFailureService::view);
    }

    @Transactional
    public Map<String, Object> replay(String id) {
        // The row lock serializes replay attempts across API replicas. A
        // concurrent caller observes REPLAYED after the first transaction
        // commits instead of executing the same external publication twice.
        IngestParseFailureEntity row = repository.findForReplay(id, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("Parse failure not found: " + id));
        if ("REPLAYED".equals(row.getReplayStatus())) {
            Map<String, Object> duplicate = new LinkedHashMap<>(view(row));
            duplicate.put("duplicate", true);
            return duplicate;
        }
        row.setReplayAttempts(row.getReplayAttempts() + 1);
        try {
            var normalized = normalizer.normalize(row.getRawPayload(), row.getCollectorId(),
                    "quarantine:" + row.getId(), normalizer.lookupSnapshot());
            IngestionCommitService.CommitResult committed = commits.commit(
                    java.util.List.of(normalized.event()));
            row.setReplayStatus("REPLAYED");
            row.setReplayedEventId(normalized.event().eventId());
            row.setReplayedAt(Instant.now());
            row.setLastError(null);
            repository.save(row);
            Map<String, Object> result = new LinkedHashMap<>(view(row));
            result.put("created", committed.created());
            result.put("duplicates", committed.duplicates());
            return result;
        } catch (IngestParseException stillInvalid) {
            row.setReplayStatus("PENDING");
            row.setLastError(normalize(stillInvalid.getMessage(), "parse failed", 1024));
            repository.save(row);
            Map<String, Object> result = new LinkedHashMap<>(view(row));
            result.put("replayed", false);
            return result;
        } catch (RuntimeException dependencyFailure) {
            // The surrounding transaction rolls back both any partial event
            // commit and this replay-state mutation. The caller can safely
            // retry the still-PENDING quarantine row.
            throw ApiException.of(503,
                    "Ingest replay persistence is unavailable; retry this quarantine row");
        }
    }

    private static Map<String, Object> view(IngestParseFailureEntity row) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", row.getId());
        value.put("collectorId", row.getCollectorId());
        value.put("rawPayload", row.getRawPayload());
        value.put("receivedAt", row.getReceivedAt());
        value.put("parserVersion", row.getParserVersion());
        value.put("failureReason", row.getFailureReason());
        value.put("replayStatus", row.getReplayStatus());
        value.put("replayAttempts", row.getReplayAttempts());
        value.put("replayedEventId", row.getReplayedEventId());
        value.put("replayedAt", row.getReplayedAt());
        value.put("lastError", row.getLastError());
        return value;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalize(String value, String fallback, int max) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
