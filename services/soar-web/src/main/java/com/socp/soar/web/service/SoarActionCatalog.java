package com.socp.soar.web.service;

import java.util.Locale;
import java.util.Map;

/** Stable action-ref vocabulary shared by the editor, validator and worker. */
public final class SoarActionCatalog {
    private static final Map<String, String> LEGACY = Map.of(
            "socp.notify/send", "notify",
            "socp.incident/create", "case",
            "socp.threat-intel/ioc.lookup", "asset-lookup",
            "net.firewall/block", "firewall-block",
            "endpoint/isolate", "network-isolate",
            "endpoint/isolate-host", "network-isolate",
            "endpoint/release-host", "network-isolate",
            "endpoint/snapshot", "snapshot",
            "endpoint/snapshot-host", "snapshot",
            "http/webhook", "webhook");

    private static final java.util.Set<String> BUILTIN = java.util.Set.of(
            "socp.alert/get", "socp.alert/add-note", "socp.alert/assign", "socp.alert/set-status",
            "socp.alert/add-tag", "socp.incident/get", "socp.incident/create",
            "socp.incident/append-timeline", "socp.incident/assign", "socp.incident/set-status",
            "socp.incident/add-task", "socp.incident/complete-task", "socp.search/search-events",
            "socp.search/get-event", "socp.asset/find-by-entity", "socp.asset/get-asset",
            "socp.threat-intel/lookup-ioc", "socp.notify/send-channel", "http.webhook/request",
            "endpoint/isolate-host", "endpoint/release-host", "endpoint/snapshot",
            "firewall/block-ioc", "firewall/unblock-ioc", "net.firewall/block");

    private SoarActionCatalog() { }

    public static String toLegacyAction(String actionRef) {
        String value = actionRef == null ? "" : actionRef.trim();
        int at = value.indexOf('@');
        String base = at < 0 ? value : value.substring(0, at);
        return LEGACY.getOrDefault(base.toLowerCase(Locale.ROOT), value);
    }

    public static boolean isKnown(String actionRef) {
        if (actionRef == null) return false;
        String value = actionRef.trim();
        int at = value.indexOf('@');
        String base = at < 0 ? value : value.substring(0, at);
        return LEGACY.containsKey(base.toLowerCase(Locale.ROOT))
                || BUILTIN.contains(base.toLowerCase(Locale.ROOT));
    }

    public static boolean isNamespaced(String actionRef) {
        if (actionRef == null) return false;
        String value = actionRef.trim();
        int at = value.indexOf('@');
        String base = at < 0 ? value : value.substring(0, at);
        return base.matches("[a-z][a-z0-9_.-]{1,63}/[a-z][a-z0-9_.-]{1,63}")
                && (at < 0 || value.substring(at + 1).matches("v?[0-9]+"));
    }
}
