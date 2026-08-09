package com.socp.search.config.store;

import com.socp.search.config.domain.SinkTarget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 输出目标存储——进程内；生产替换为 PG search.t_sink_target，接口不变。
 * 默认种子：SEARCH 自身 ingest（渲染器兜底目标）。
 */
@Component
public class SinkTargetStore {

    private final ConcurrentHashMap<String, SinkTarget> map = new ConcurrentHashMap<>();

    public SinkTargetStore() {
        save(SinkTarget.create("SEARCH 默认 ingest", "GLS_INGEST",
                "http://localhost:18081/search-config/api/v1/ingest", null, true));
        save(SinkTarget.create("OpenSearch 索引", "OPENSEARCH",
                "http://localhost:9200/_bulk", null, false));
    }

    public List<SinkTarget> list() {
        return map.values().stream().toList();
    }

    public List<SinkTarget> enabled() {
        return list().stream().filter(SinkTarget::enabled).toList();
    }

    public SinkTarget save(SinkTarget t) {
        map.put(t.id(), t);
        return t;
    }

    public SinkTarget get(String id) {
        return map.get(id);
    }

    public boolean delete(String id) {
        return map.remove(id) != null;
    }
}
