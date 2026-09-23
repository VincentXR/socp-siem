package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.persistence.store.LogSourceStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
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
