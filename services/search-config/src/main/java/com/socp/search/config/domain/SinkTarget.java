package com.socp.search.config.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 输出目标——解析后的事件投递到哪（SEARCH ingest / OpenSearch / 任意 HTTP）。
 * 空 id 的默认目标为 SEARCH 自身 ingest（渲染器兜底）。
 */
public record SinkTarget(
        String id,
        @NotBlank @Size(max = 128)
        String name,
        @NotBlank @Size(max = 32)
        String type,
        /** 完整 URL，如 http://localhost:18081/search-config/api/v1/ingest 或 http://os:9200/_bulk */
        @NotBlank @Size(max = 2048)
        @Pattern(regexp = "(?i)^(https?|kafka|opensearch)://.*$")
        String uri,
        /** 可选认证头，如 Bearer xxx */
        @Size(max = 4096) String authToken,
        boolean enabled,
        Instant createdAt
) {
    public static SinkTarget create(String name, String type, String uri, String authToken, boolean enabled) {
        return new SinkTarget(UUID.randomUUID().toString(), name, type, uri, authToken, enabled, Instant.now());
    }
}
