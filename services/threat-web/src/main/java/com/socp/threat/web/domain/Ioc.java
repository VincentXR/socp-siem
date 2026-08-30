package com.socp.threat.web.domain;

import java.time.Instant;
import java.util.Locale;
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
        Instant lastSeen,
        String feed,
        String externalId,
        Double confidence,
        String tlp,
        Instant validFrom,
        Instant validUntil,
        Instant expiration,
        boolean revoked,
        String provenance) {

    /** Source-compatible constructor for the original local IOC contract. */
    public Ioc(String id, String type, String value, String severity, String source,
               String description, List<String> tags, Instant firstSeen, Instant lastSeen) {
        this(id, type, value, severity, source, description, tags, firstSeen, lastSeen,
                source, null, null, null, firstSeen, null, null, false, "manual");
    }

    public static Ioc of(String type, String value, String severity, String source,
                         String description, List<String> tags) {
        String id = (type + ":" + value.toLowerCase(Locale.ROOT)).replaceAll("[^A-Za-z0-9:._-]", "_");
        Instant now = Instant.now();
        return new Ioc(id, type.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT),
                normSev(severity), source, description, tags, now, now,
                source, null, null, null, now, null, null, false, "manual");
    }

    /** Build an IOC imported from STIX/TAXII while retaining feed provenance. */
    public static Ioc external(String type, String value, String severity, String feed,
                               String externalId, String description, List<String> tags,
                               Double confidence, String tlp, Instant validFrom,
                               Instant validUntil, Instant expiration, boolean revoked,
                               String provenance) {
        Instant now = Instant.now();
        String normalizedValue = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String id = (type + ":" + normalizedValue).replaceAll("[^A-Za-z0-9:._-]", "_");
        return new Ioc(id, type.toUpperCase(Locale.ROOT), normalizedValue, normSev(severity),
                feed, description, tags == null ? List.of() : List.copyOf(tags), now, now,
                feed, externalId, confidence, tlp, validFrom == null ? now : validFrom,
                validUntil, expiration, revoked, provenance == null ? "taxii" : provenance);
    }

    public static String normSev(String s) {
        if (s == null) return "MEDIUM";
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO" -> s.toUpperCase();
            default -> "MEDIUM";
        };
    }

    /**
     * Returns whether this indicator is eligible for enrichment at the given
     * instant.  Revocation always wins over the validity window; expired
     * indicators remain queryable through the list APIs for audit purposes but
     * are never returned by exact-match enrichment.
     */
    public boolean isActiveAt(Instant instant) {
        Instant at = instant == null ? Instant.now() : instant;
        if (revoked) return false;
        if (validFrom != null && at.isBefore(validFrom)) return false;
        if (validUntil != null && !at.isBefore(validUntil)) return false;
        return expiration == null || at.isBefore(expiration);
    }
}
