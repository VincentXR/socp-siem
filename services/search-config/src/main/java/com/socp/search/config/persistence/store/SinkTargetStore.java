package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.config.VectorProperties;
import com.socp.search.config.domain.SinkTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 输出目标存储——租户自持行落 PG search.t_sink_target。
 *
 * <p>平台内置目标（SEARCH ingest / OpenSearch）不再注册为租户目录模板：它们只是
 * 按稳定 id 寻址的回退元数据，既不出现在任何租户的 list() 结果里，也不会被渲染器
 * 隐式抢占。渲染时按 {@code LogSource.sinkTargetId} 选租户目标，未绑定才回落到平台
 * 目标；两者都不可用时渲染失败，而不是兜底到进程内硬编码地址。
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class SinkTargetStore {

    /** 平台内置 SEARCH ingest 目标的稳定 id。 */
    public static final String PLATFORM_INGEST_ID = "platform-search-ingest";
    /** 平台内置 OpenSearch 目标的稳定 id（默认不可路由，仅占位说明）。 */
    public static final String PLATFORM_OPENSEARCH_ID = "platform-opensearch-bulk";

    private final TenantCatalog<SinkTarget> catalog;
    private final List<SinkTarget> platformTargets;

    public SinkTargetStore() {
        this(null, null, new VectorProperties());
    }

    @Autowired
    public SinkTargetStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper,
                           VectorProperties vectorProperties) {
        this.catalog = persistence == null
                ? new TenantCatalog<>(SinkTarget::id)
                : new TenantCatalog<>(SinkTarget::id, "sink_target", SinkTarget.class,
                persistence, objectMapper);
        String ingestUri = vectorProperties.getUri() == null ? "" : vectorProperties.getUri().trim();
        Instant created = Instant.now();
        // Platform metadata never carries a credential: the collector token is injected
        // by the renderer at request time and is redacted unless explicitly requested.
        this.platformTargets = List.of(
                new SinkTarget(PLATFORM_INGEST_ID, "平台 SEARCH ingest", "GLS_INGEST",
                        ingestUri, null, !ingestUri.isBlank(), created),
                new SinkTarget(PLATFORM_OPENSEARCH_ID, "平台 OpenSearch 索引（未启用）", "OPENSEARCH",
                        "", null, false, created));
    }

    /** 租户自有输出目标；平台回退元数据不在其中。 */
    public List<SinkTarget> list() {
        return catalog.list();
    }

    /** 平台回退元数据，按稳定 id 寻址。 */
    public List<SinkTarget> platformTargets() {
        return platformTargets;
    }

    public SinkTarget get(String id) {
        SinkTarget tenantTarget = catalog.get(id);
        return tenantTarget == null ? platformTarget(id) : tenantTarget;
    }

    public SinkTarget platformTarget(String id) {
        if (id == null) return null;
        return platformTargets.stream()
                .filter(target -> id.equals(target.id()))
                .findFirst().orElse(null);
    }

    /**
     * Rendering authority for one source binding: an explicit binding resolves inside the
     * tenant's own targets first and only then against platform metadata; an unbound source
     * uses the platform ingest target. Availability (enabled/uri) is judged by the renderer.
     */
    public SinkTarget resolveForRendering(String sinkTargetId) {
        if (sinkTargetId == null || sinkTargetId.isBlank()) {
            return platformTarget(PLATFORM_INGEST_ID);
        }
        SinkTarget tenantTarget = catalog.get(sinkTargetId);
        return tenantTarget == null ? platformTarget(sinkTargetId) : tenantTarget;
    }

    public SinkTarget save(SinkTarget t) {
        return catalog.save(t);
    }

    /** 平台回退元数据不可被租户删除；未知 id 不再写墓碑。 */
    public boolean delete(String id) {
        return platformTarget(id) == null && catalog.delete(id);
    }
}
