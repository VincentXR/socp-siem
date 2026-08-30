package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.SinkTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输出目标存储——进程内；生产替换为 PG search.t_sink_target，接口不变。
 * 默认种子：SEARCH 自身 ingest（渲染器兜底目标）。
 */
@Component
public class SinkTargetStore {

    private final TenantCatalog<SinkTarget> catalog;
    private boolean seeding = true;

    public SinkTargetStore() {
        this(null, null);
    }

    @Autowired
    public SinkTargetStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistence == null
                ? new TenantCatalog<>(SinkTarget::id)
                : new TenantCatalog<>(SinkTarget::id, "sink_target", SinkTarget.class,
                persistence, objectMapper);
        save(SinkTarget.create("SEARCH 默认 ingest", "GLS_INGEST",
                "http://localhost:18081/search-config/api/v1/ingest", null, true));
        save(SinkTarget.create("OpenSearch 索引", "OPENSEARCH",
                "http://localhost:9200/_bulk", null, false));
        seeding = false;
    }

    public List<SinkTarget> list() {
        return catalog.list();
    }

    public List<SinkTarget> enabled() {
        return list().stream().filter(SinkTarget::enabled).toList();
    }

    public SinkTarget save(SinkTarget t) {
        if (seeding) {
            catalog.registerTemplate(t);
            return t;
        }
        return catalog.save(t);
    }

    public SinkTarget get(String id) {
        return catalog.get(id);
    }

    public boolean delete(String id) {
        return catalog.delete(id);
    }
}
