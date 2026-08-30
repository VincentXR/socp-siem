package com.socp.threat.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.threat.web.domain.Ioc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict STIX 2.1 indicator projection used by TAXII imports and fixtures. */
public final class StixIndicatorImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STIX_PATTERN = Pattern.compile(
            "\\[\\s*([a-z0-9-]+):value\\s*=\\s*'((?:\\\\'|[^'])*)'\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    public ImportResult parse(String bundle, String feed) {
        if (bundle == null || bundle.isBlank()) throw invalid("STIX bundle is empty");
        Map<String, Object> root;
        try {
            root = JSON.readValue(bundle, new TypeReference<>() { });
        } catch (Exception ex) {
            throw invalid("invalid STIX JSON: " + ex.getMessage());
        }
        Object rawObjects = root.get("objects");
        if (!(rawObjects instanceof List<?> objects)) throw invalid("STIX bundle.objects must be an array");
        List<Ioc> indicators = new ArrayList<>();
        int skipped = 0;
        for (Object raw : objects) {
            if (!(raw instanceof Map<?, ?> value)) { skipped++; continue; }
            Map<String, Object> object = new LinkedHashMap<>();
            value.forEach((key, item) -> object.put(String.valueOf(key), item));
            if (!"indicator".equalsIgnoreCase(text(object.get("type")))) { skipped++; continue; }
            Ioc ioc = parseIndicator(object, feed);
            if (ioc == null) skipped++; else indicators.add(ioc);
        }
        return new ImportResult(List.copyOf(indicators), skipped);
    }

    private static Ioc parseIndicator(Map<String, Object> object, String feed) {
        String pattern = text(object.get("pattern"));
        if (pattern == null) throw invalid("STIX indicator.pattern is required");
        Matcher matcher = STIX_PATTERN.matcher(pattern);
        if (!matcher.matches()) throw invalid("unsupported STIX indicator pattern: " + pattern);
        String type = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "ipv4-addr", "ipv6-addr" -> "IP";
            case "domain-name" -> "DOMAIN";
            case "url" -> "URL";
            case "email-addr" -> "EMAIL";
            case "file" -> fileHashType(pattern);
            default -> throw invalid("unsupported STIX observable type: " + matcher.group(1));
        };
        String value = matcher.group(2).replace("\\'", "'");
        List<String> labels = list(object.get("labels"));
        String tlp = labels.stream().filter(item -> item.toLowerCase(Locale.ROOT).startsWith("tlp:")
                || item.toLowerCase(Locale.ROOT).startsWith("tlp-")).findFirst().orElse(null);
        return Ioc.external(type, value, severity(object.get("labels")),
                feed == null || feed.isBlank() ? "taxii" : feed,
                text(object.get("id")), text(object.get("description")), labels,
                number(object.get("confidence")), tlp, instant(object.get("valid_from")),
                instant(object.get("valid_until")), instant(object.get("expiration")),
                Boolean.TRUE.equals(object.get("revoked")), "stix-2.1");
    }

    private static String fileHashType(String pattern) {
        String lower = pattern.toLowerCase(Locale.ROOT);
        if (lower.contains("sha-256") || lower.contains("sha256")) return "SHA256";
        if (lower.contains("md5")) return "MD5";
        throw invalid("file indicator must select a supported hash algorithm");
    }

    private static String severity(Object labels) {
        for (String label : list(labels)) {
            String normalized = label.toLowerCase(Locale.ROOT);
            if (normalized.contains("critical")) return "CRITICAL";
            if (normalized.contains("high")) return "HIGH";
            if (normalized.contains("low")) return "LOW";
        }
        return "MEDIUM";
    }

    private static List<String> list(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(StixIndicatorImporter::text).filter(item -> item != null).toList();
    }

    private static Double number(Object value) {
        if (value instanceof Number number) return Math.max(0, Math.min(100, number.doubleValue()));
        return null;
    }

    private static Instant instant(Object value) {
        String text = text(value);
        if (text == null) return null;
        try { return Instant.parse(text); }
        catch (RuntimeException ex) { throw invalid("invalid STIX timestamp: " + text); }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public record ImportResult(List<Ioc> indicators, int skipped) {
        public ImportResult {
            indicators = List.copyOf(indicators);
        }
    }
}
