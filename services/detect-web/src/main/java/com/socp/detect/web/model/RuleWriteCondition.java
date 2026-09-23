package com.socp.detect.web.model;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.platform.error.exception.ApiException;
import com.socp.rule.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded authoring preconditions, evaluated under the tenant catalogue lock. */
public record RuleWriteCondition(List<String> tags, boolean absent) {
    public static final String TOKEN = "revisionToken";
    private static final Pattern ENTITY_TAG = Pattern.compile("(?:W/)?\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"");

    public RuleWriteCondition { tags = List.copyOf(tags); }

    public static RuleWriteCondition parse(String ifMatch, String ifNoneMatch, boolean allowAbsent) {
        if (ifNoneMatch != null) {
            if (ifMatch != null || !allowAbsent || !"*".equals(ifNoneMatch.trim())) {
                throw ApiException.badRequest("restore accepts either If-Match or If-None-Match: *");
            }
            return new RuleWriteCondition(List.of(), true);
        }
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            throw ApiException.of(428, "read the current rule and supply its specific ETag in If-Match");
        }
        if (ifMatch.length() > 2048) throw ApiException.badRequest("If-Match exceeds 2048 characters");
        var matcher = ENTITY_TAG.matcher(ifMatch);
        var tags = new ArrayList<String>();
        int end = 0;
        while (matcher.find()) {
            String separator = ifMatch.substring(end, matcher.start()).trim();
            if (!separator.equals(tags.isEmpty() ? "" : ",") || tags.size() == 8) {
                throw ApiException.badRequest("If-Match requires at most eight quoted entity tags");
            }
            tags.add(matcher.group());
            end = matcher.end();
        }
        if (tags.isEmpty() || !ifMatch.substring(end).isBlank()) {
            throw ApiException.badRequest("If-Match requires quoted entity tags");
        }
        return new RuleWriteCondition(tags, false);
    }

    public void check(Map<String, Object> current) {
        if (absent ? current != null : current == null || !tags.contains(etag(current))) {
            throw ApiException.of(412, "rule changed or was deleted; reload and review before retrying");
        }
    }

    public static String etag(Map<String, Object> spec) {
        Object token = spec.get(TOKEN);
        if (!(token instanceof String value) || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("rule response has no revision token");
        }
        return "\"" + token + "\"";
    }

    /** Hash the complete enriched stored representation, including its server nonce. */
    public static Map<String, Object> representation(String tenant, Map<String, Object> stored) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(tenant.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Json.mapper().writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(stored));
            var result = new LinkedHashMap<>(stored);
            result.put(TOKEN, HexFormat.of().formatHex(digest.digest()));
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("unable to fingerprint rule representation", failure);
        }
    }
}
