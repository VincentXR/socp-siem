package com.socp.search.config.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 输出目标——SEARCH ingest 或兼容当前 NDJSON 编码的 HTTP 接收器。
 * 空 id 的默认目标为 SEARCH 自身 ingest（渲染器兜底）。
 */
public record SinkTarget(
        String id,
        @NotBlank @Size(max = 128)
        String name,
        @NotBlank @Size(max = 32)
        String type,
        /** 完整 HTTP(S) URL；保留旧记录的反序列化兼容，写入和渲染入口拒绝不支持的协议。 */
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
