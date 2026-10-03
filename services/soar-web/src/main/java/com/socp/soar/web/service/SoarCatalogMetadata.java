package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.TreeSet;

/** Queryable metadata derived atomically whenever the corresponding JSON value changes. */
public final class SoarCatalogMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SoarCatalogMetadata() { }
    public static String tagToken(String tag) {
        return "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tag.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) + "|";
    }
    public static String tagTokens(String json) {
        try {
            var root = JSON.readTree(json == null ? "[]" : json);
            TreeSet<String> tokens = new TreeSet<>();
            if (root != null && root.isArray()) root.forEach(value -> tokens.add(tagToken(value.asText())));
            return String.join("", tokens);
        } catch (Exception ignored) { return ""; }
    }
    public static int[] riskCounts(String json) {
        try {
            var root = JSON.readTree(json == null ? "{}" : json);
            return root == null ? new int[] { 0, 0 } : new int[] {
                    Math.max(0, root.path("highRiskActionCount").asInt(0)),
                    Math.max(0, root.path("actionCount").asInt(0)) };
        } catch (Exception ignored) { return new int[] { 0, 0 }; }
    }
}
