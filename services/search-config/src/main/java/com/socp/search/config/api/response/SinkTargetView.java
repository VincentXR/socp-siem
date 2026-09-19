package com.socp.search.config.api.response;

import com.socp.search.config.domain.SinkTarget;

import java.time.Instant;

/**
 * 输出目标的读视图——{@code authToken} 永不出网：响应只暴露是否存在凭据，
 * 明文只在渲染 Vector 配置且调用方具备取回凭据的授权时才可能出现。
 */
public record SinkTargetView(
        String id,
        String name,
        String type,
        String uri,
        boolean authTokenConfigured,
        boolean enabled,
        Instant createdAt
) {
    public static SinkTargetView of(SinkTarget target) {
        return new SinkTargetView(target.id(), target.name(), target.type(), target.uri(),
                target.authToken() != null && !target.authToken().isBlank(),
                target.enabled(), target.createdAt());
    }
}
