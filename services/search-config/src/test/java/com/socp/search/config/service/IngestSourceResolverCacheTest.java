package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestSourceResolverCacheTest {

    @Test
    void resolvedSourceCarriesEventTimeConfiguration() {
        LogSourceStore sources = mock(LogSourceStore.class);
        LogSource source = LogSource.createFull("app", SourceType.FILE, ParseFormat.JSON,
                "/var/log/app.log", null, null, "prod", true,
                "beginning", null, null, List.of(), null,
                null, "utf-8", "occurred_local", "Asia/Shanghai", List.of(), 1,
                null, null, null);
        when(sources.get("collector-1")).thenReturn(Optional.of(source));
        IngestSourceResolver resolver = new IngestSourceResolver(sources);
        TenantContext.set("tenant-a");

        IngestSourceContext context = resolver.resolve("{}", "collector-1");

        assertEquals("occurred_local", context.timeField());
        assertEquals("Asia/Shanghai", context.timezone());
    }

    @Test
    void concurrentDistinctSourceHintsRespectTheCacheBound() throws Exception {
        LogSourceStore sources = mock(LogSourceStore.class);
        when(sources.get(anyString())).thenReturn(Optional.empty());
        when(sources.findByCollectorTag(anyString())).thenReturn(Optional.empty());
        IngestSourceResolver resolver = new IngestSourceResolver(sources);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int group = worker;
                futures.add(workers.submit(() -> {
                    TenantContext.set("tenant-a");
                    try {
                        for (int index = 0; index < 512; index++) {
                            resolver.resolve("line", "source-" + group + "-" + index);
                        }
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2048, resolver.cachedSources());
    }

    @Test
    void aSourceWriteDuringLookupCannotRestoreAnOldCacheEntry() throws Exception {
        LogSourceStore sources = mock(LogSourceStore.class);
        AtomicLong revision = new AtomicLong();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(sources.revision(anyString())).thenAnswer(ignored -> revision.get());
        when(sources.get("candidate")).thenAnswer(ignored -> {
            reading.countDown();
            assertTrue(resume.await(10, TimeUnit.SECONDS));
            return Optional.empty();
        });
        when(sources.findByCollectorTag("candidate")).thenReturn(Optional.empty());
        IngestSourceResolver resolver = new IngestSourceResolver(sources);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var inFlight = worker.submit(() -> {
                TenantContext.set("tenant-a");
                try { resolver.resolve("line", "candidate"); }
                finally { TenantContext.clear(); }
            });
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            revision.incrementAndGet();
            resume.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, resolver.cachedSources());
        TenantContext.set("tenant-a");
        try { resolver.resolve("line", "candidate"); }
        finally { TenantContext.clear(); }
        verify(sources, times(2)).get("candidate");
        assertEquals(1, resolver.cachedSources());
    }
}
