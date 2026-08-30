package com.socp.threat.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.persistence.entity.TaxiiCheckpointEntity;
import com.socp.threat.web.persistence.repository.TaxiiCheckpointRepository;
import com.socp.threat.web.persistence.store.IocStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pulls TAXII pages, applies STIX projection, and checkpoints only after durable writes. */
@Service
public class TaxiiSyncService {
    private final IocStore store;
    private final TaxiiCheckpointRepository checkpoints;

    public TaxiiSyncService(IocStore store, TaxiiCheckpointRepository checkpoints) {
        this.store = store;
        this.checkpoints = checkpoints;
    }

    public Map<String, Object> sync(String feed, URI collection, String authorization,
                                    boolean allowHttp) {
        String normalizedFeed = feed == null || feed.isBlank() ? "taxii" : feed.trim();
        if (!normalizedFeed.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("TAXII feed contains unsupported characters");
        }
        if (collection == null || collection.getHost() == null) {
            throw new IllegalArgumentException("TAXII collection URL is invalid");
        }
        String tenant = TenantContext.require();
        TaxiiClient client = new TaxiiClient(Duration.ofSeconds(10), allowHttp);
        TaxiiCheckpointEntity checkpoint = checkpoints.findByTenantIdAndFeed(tenant, normalizedFeed)
                .orElseGet(TaxiiCheckpointEntity::new);
        if (checkpoint.getCheckpointId() == null) {
            checkpoint.setCheckpointId(tenant + "|" + normalizedFeed);
        }
        checkpoint.setFeed(normalizedFeed);
        checkpoint.setTenantId(tenant);
        checkpoint.setCollectionUrl(collection.toString());
        if (checkpoint.getLastSyncedAt() == null) checkpoint.setLastSyncedAt(Instant.EPOCH);
        try {
            var pages = client.fetchCollection(collection, authorization);
            int imported = 0;
            int skipped = 0;
            int revoked = 0;
            for (String page : pages) {
                StixIndicatorImporter.ImportResult result = new StixIndicatorImporter().parse(page, normalizedFeed);
                skipped += result.skipped();
                for (Ioc indicator : result.indicators()) {
                    store.add(indicator);
                    imported++;
                    if (indicator.revoked()) revoked++;
                }
            }
            checkpoint.setLastPage(pages.size());
            checkpoint.setLastSyncedAt(Instant.now());
            checkpoint.setLastError(null);
            checkpoints.save(checkpoint);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("feed", normalizedFeed);
            response.put("pages", pages.size());
            response.put("imported", imported);
            response.put("skipped", skipped);
            response.put("revoked", revoked);
            response.put("lastSyncedAt", checkpoint.getLastSyncedAt().toString());
            response.put("tenant", tenant);
            return response;
        } catch (RuntimeException failure) {
            checkpoint.setLastError(failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage().substring(0, Math.min(1024, failure.getMessage().length())));
            checkpoints.save(checkpoint);
            throw failure;
        }
    }
}
