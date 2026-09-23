package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class ParsePipelineResolverCacheTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void distinctSourcesCannotGrowCompiledPipelineCacheWithoutBound() {
        ParseRuleStore rules = mock(ParseRuleStore.class);
        when(rules.enabled()).thenReturn(List.of());
        ParsePipelineResolver resolver = new ParsePipelineResolver(
                rules, new ParseRuleExecutor(new ParserRegistry()));
        TenantContext.set("tenant-a");

        for (int index = 0; index < 4096; index++) {
            var context = new IngestSourceContext("collector", "source-" + index,
                    ParseFormat.AUTO, List.of(), true);
            resolver.apply(context, "line", "line", true);
            assertTrue(resolver.cachedPipelines() <= 2048);
        }

        assertEquals(2048, resolver.cachedPipelines());
        var latest = new IngestSourceContext("collector", "source-4095",
                ParseFormat.AUTO, List.of(), true);
        resolver.apply(latest, "line", "line", true);
        assertEquals(2048, resolver.cachedPipelines());
    }

    @Test
    void concurrentNewSourcesRespectTheSameCacheBound() throws Exception {
        ParseRuleStore rules = mock(ParseRuleStore.class);
        when(rules.enabled()).thenReturn(List.of());
        ParsePipelineResolver resolver = new ParsePipelineResolver(
                rules, new ParseRuleExecutor(new ParserRegistry()));
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int group = worker;
                futures.add(workers.submit(() -> {
                    TenantContext.set("tenant-a");
                    try {
                        for (int index = 0; index < 512; index++) {
                            var context = new IngestSourceContext("collector",
                                    "source-" + group + "-" + index,
                                    ParseFormat.AUTO, List.of(), true);
                            resolver.apply(context, "line", "line", true);
                        }
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2048, resolver.cachedPipelines());
    }

    @Test
    void aRuleWriteDuringCompilationCannotRestoreAnOldCacheEntry() throws Exception {
        ParseRuleStore rules = mock(ParseRuleStore.class);
        AtomicLong revision = new AtomicLong();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(rules.revision(anyString())).thenAnswer(ignored -> revision.get());
        when(rules.enabled()).thenAnswer(ignored -> {
            reading.countDown();
            assertTrue(resume.await(10, TimeUnit.SECONDS));
            return List.of();
        });
        ParsePipelineResolver resolver = new ParsePipelineResolver(
                rules, new ParseRuleExecutor(new ParserRegistry()));
        var context = new IngestSourceContext("collector", "source-a",
                ParseFormat.AUTO, List.of(), true);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var inFlight = worker.submit(() -> {
                TenantContext.set("tenant-a");
                try { resolver.apply(context, "line", "line", true); }
                finally { TenantContext.clear(); }
            });
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            revision.incrementAndGet();
            resume.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, resolver.cachedPipelines());
        TenantContext.set("tenant-a");
        resolver.apply(context, "line", "line", true);
        assertEquals(1, resolver.cachedPipelines());
    }
}
