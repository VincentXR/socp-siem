package com.socp.search.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.config.ConfigCacheProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.persistence.store.LogSourceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the server-owned source configuration for one Vector envelope.
 * Body metadata is only a lookup hint; tenant ownership still comes from the
 * authenticated request and parsing rules come from the persisted LogSource.
 *
 * <p>The cache is invalidated per tenant (another tenant's write no longer flushes this
 * tenant's resolved sources) and refreshed at least every configuration-cache TTL so a
 * source changed through another SEARCH replica stops being served from this cache.
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class IngestSourceResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CACHE_ENTRIES = 2048;

    private final LogSourceStore sources;
    private final Map<String, Optional<LogSource>> cache = new ConcurrentHashMap<>();
    private final TenantCacheGuard guard;

    public IngestSourceResolver(LogSourceStore sources) {
        this(sources, new ConfigCacheProperties());
    }

    @Autowired
    public IngestSourceResolver(LogSourceStore sources, ConfigCacheProperties properties) {
        this.sources = sources;
        this.guard = new TenantCacheGuard(properties.getTtlMs());
    }

    public IngestSourceContext resolve(String raw, String trustedCollector) {
        EnvelopeMetadata metadata = metadata(raw);

        // A credential may be deliberately named after a source id. Prefer
        // that trusted exact match before considering body metadata.
        LogSource source = lookup(trustedCollector).orElse(null);
        if (source == null) source = lookup(metadata.sourceId()).orElse(null);
        if (source == null) source = lookup(metadata.collectorTag()).orElse(null);

        if (source != null) {
            return new IngestSourceContext(
                    trustedCollector == null || trustedCollector.isBlank()
                            ? source.collectorTag() : trustedCollector,
                    source.id(), source.format(), source.parseRuleIds(), true);
        }
        return IngestSourceContext.unresolved(trustedCollector, metadata.format());
    }

    private Optional<LogSource> lookup(String candidate) {
        if (candidate == null || candidate.isBlank()) return Optional.empty();
        String tenant = TenantContext.require();
        long revision = sources.revision(tenant);
        if (guard.isStale(tenant, revision)) {
            synchronized (cache) {
                long current = sources.revision(tenant);
                if (guard.isStale(tenant, current)) {
                    cache.keySet().removeIf(key -> key.startsWith(tenant + "|"));
                    guard.markFresh(tenant, current);
                }
            }
        }
        String key = tenant + "|" + candidate.trim();
        Optional<LogSource> cached = cache.get(key);
        if (cached != null) return cached;
        Optional<LogSource> resolved = sources.get(candidate.trim());
        if (resolved.isEmpty()) resolved = sources.findByCollectorTag(candidate.trim());
        synchronized (cache) {
            cached = cache.get(key);
            if (cached != null) return cached;
            // A mutation during the database read must not repopulate an invalidated entry.
            if (sources.revision(tenant) != revision) return resolved;
            while (cache.size() >= MAX_CACHE_ENTRIES) {
                var entries = cache.keySet().iterator();
                if (!entries.hasNext()) break;
                cache.remove(entries.next());
            }
            cache.put(key, resolved);
            return resolved;
        }
    }

    int cachedSources() {
        return cache.size();
    }

    private static EnvelopeMetadata metadata(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("{")) {
            return new EnvelopeMetadata(null, null, ParseFormat.AUTO);
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                return new EnvelopeMetadata(null, null, ParseFormat.AUTO);
            }
            String sourceId = text(root, "source_id");
            String collectorTag = text(root, "collector_tag");
            ParseFormat format = parseFormat(text(root, "parse_format"));
            return new EnvelopeMetadata(sourceId, collectorTag, format);
        } catch (Exception ignored) {
            return new EnvelopeMetadata(null, null, ParseFormat.AUTO);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static ParseFormat parseFormat(String value) {
        if (value == null || value.isBlank()) return ParseFormat.AUTO;
        try {
            return ParseFormat.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ParseFormat.AUTO;
        }
    }

    private record EnvelopeMetadata(String sourceId, String collectorTag, ParseFormat format) {
    }
}
