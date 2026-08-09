package com.socp.threat.web.domain;

import java.time.Instant;
import java.util.List;

/**
 * 失陷指标（Indicator of Compromise）。
 * 大厂 SIEM 的威胁情报底座：从商业/开源情报源（AlienVault OTX、MISP、VirusTotal 等）
 * 汇入，经归一化后用于实时检测富化与狩猎检索。
 *
 * @param type   指标类型：IP / DOMAIN / URL / SHA256 / MD5 / EMAIL
 * @param value  指标值（归一化小写，便于精确匹配）
 * @param severity 威胁级别（情报研判置信度）
 * @param source 情报来源（feed 名称）
 * @param tags   标签（如 malware、phishing、c2、tor）
 */
public record Ioc(
        String id,
        String type,
        String value,
        String severity,
        String source,
        String description,
        List<String> tags,
        Instant firstSeen,
        Instant lastSeen) {

    public static Ioc of(String type, String value, String severity, String source,
                         String description, List<String> tags) {
        String id = (type + ":" + value.toLowerCase()).replaceAll("[^A-Za-z0-9:._-]", "_");
        return new Ioc(id, type.toUpperCase(), value.toLowerCase(),
                normSev(severity), source, description, tags, Instant.now(), Instant.now());
    }

    public static String normSev(String s) {
        if (s == null) return "MEDIUM";
        return switch (s.toUpperCase()) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO" -> s.toUpperCase();
            default -> "MEDIUM";
        };
    }
}
