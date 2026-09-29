package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.SearchCacheProperties;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.repository.LogSourceRepository;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.SearchStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves source event-time settings reach the database and durable Kafka publication payload. */
@DataJpaTest(showSql = false)
class ConfiguredEventTimePersistenceTest {

    @Autowired LogSourceRepository sourceRepository;
    @Autowired SearchEventRepository eventRepository;
    @Autowired IngestionOutboxRepository outboxRepository;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setTenant() {
        TenantContext.set("tenant-time");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void configuredLocalTimeIsPersistedAndPublishedAsTheSameInstant() {
        LogSourceStore sources = new LogSourceStore(sourceRepository);
        LogSource source = LogSource.createFull("application", SourceType.FILE, ParseFormat.JSON,
                "/var/log/application.log", null, null, "prod", true,
                "beginning", null, null, List.of(), null,
                null, "utf-8", "occurred_local", "Asia/Shanghai", List.of(), 1,
                null, null, null);
        sources.save(source);
        IngestEventNormalizer normalizer = new IngestEventNormalizer(
                null, new ParserRegistry(), new IngestSourceResolver(sources), null);
        String raw = "{\"eventId\":\"event-time-1\",\"source_id\":\"" + source.id()
                + "\",\"source\":\"auth\",\"host\":\"server-1\","
                + "\"occurred_local\":\"2026-09-29 10:15:30\",\"message\":\"login\"}";
        var normalized = normalizer.normalize(raw, source.collectorTag());
        SearchStore searchStore = new SearchStore(eventRepository, new SearchCacheProperties(), false);
        IngestionCommitService commits = new IngestionCommitService(
                eventRepository, outboxRepository, searchStore);

        new TransactionTemplate(transactions).executeWithoutResult(
                ignored -> commits.commit(List.of(normalized.event())));

        Instant expected = Instant.parse("2026-09-29T02:15:30Z");
        var stored = eventRepository.findByTenantIdAndEventIdIn(
                "tenant-time", List.of("event-time-1")).getFirst();
        assertThat(stored.getTimestamp()).isEqualTo(expected);
        var publication = outboxRepository.findByTenantIdAndEventIdIn(
                "tenant-time", List.of("event-time-1")).getFirst();
        assertThat(publication.getPayload()).contains("\"timestamp\":\"2026-09-29T02:15:30Z\"");
    }
}
