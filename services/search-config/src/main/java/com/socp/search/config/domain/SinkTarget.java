package com.socp.search.config.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 输出目标——解析后的事件投递到哪（SEARCH ingest / OpenSearch / 任意 HTTP）。
 * 空 id 的默认目标为 SEARCH 自身 ingest（渲染器兜底）。
 */
public record SinkTarget(
        String id,
        String name,
        String type,
        /** 完整 URL，如 http://localhost:18081/search-config/api/v1/ingest 或 http://os:9200/_bulk */
        String uri,
        /** 可选认证头，如 Bearer xxx */
        String authToken,
        boolean enabled,
        Instant createdAt
) {
    public static SinkTarget create(String name, String type, String uri, String authToken, boolean enabled) {
        return new SinkTarget(UUID.randomUUID().toString(), name, type, uri, authToken, enabled, Instant.now());
    }
}
