package com.socp.hips.web.domain;
import java.time.Instant;
import java.util.UUID;

/**
 * HIPS 端点资产——已注册的运行时检测 Agent。
 */
public record Endpoint(
        String id,
        String hostname,
        String ip,
        String os,
        String agentVersion,
        String status,
        Instant lastHeartbeat
) {
    public static Endpoint register(String hostname, String ip, String os, String agentVersion) {
        return new Endpoint(UUID.randomUUID().toString(), hostname, ip, os, agentVersion,
                "ONLINE", Instant.now());
    }
}
