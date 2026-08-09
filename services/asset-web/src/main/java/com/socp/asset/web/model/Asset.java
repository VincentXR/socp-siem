package com.socp.asset.web.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 安全资产实体——覆盖主机/网络设备/应用等。
 */
public record Asset(
        String id,
        String name,
        String type,
        String ip,
        String os,
        String owner,
        String criticality,
        Instant createdAt
) {
    public static Asset create(String name, String type, String ip, String os, String owner, String criticality) {
        return new Asset(UUID.randomUUID().toString(), name, type, ip, os, owner, criticality, Instant.now());
    }
}
