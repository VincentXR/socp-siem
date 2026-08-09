package com.socp.search.config.domain;

import java.util.List;

/**
 * 查找表 / 参考数据集（大厂 SIEM 的 Lookup / Reference Data / Watchlist）。
 * 用于字段富化与检测条件引用，如 critical_assets（核心资产）、vip_users（关键人员）、
 * blocked_ips（封禁名单）、threat_actors（威胁组织）。等价于 Splunk 的 lookup 表。
 */
public record ReferenceSet(String id, String name, String description, List<String> entries) {

    public static ReferenceSet of(String name, String description, List<String> entries) {
        String id = "REF-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new ReferenceSet(id, name, description, List.copyOf(entries));
    }
}
