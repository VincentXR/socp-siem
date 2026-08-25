package com.socp.soar.web.domain;
import java.util.Locale;

/**
 * Supported playbook action families.
 *
 * <p>Playbooks are intentionally kept as a list of strings for API compatibility, but
 * execution must never infer success from an unrecognised string.  This type is the
 * boundary between the legacy string contract and the executor's typed action model.
 */
public enum PlaybookActionType {
    WEBHOOK(false),
    NOTIFY(false),
    CASE(false),
    FIREWALL_BLOCK(false),
    NETWORK_ISOLATE(false),
    SNAPSHOT(false),
    ASSET_LOOKUP(false),
    TAG(true),
    SIMULATED(true),
    UNKNOWN(false);

    private final boolean simulated;

    PlaybookActionType(boolean simulated) {
        this.simulated = simulated;
    }

    public boolean simulated() {
        return simulated;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Resolve a legacy action string without changing the public API representation. */
    public static PlaybookActionType resolve(String action) {
        String value = action == null ? "" : action.trim();
        String normalized = stripCompensation(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return UNKNOWN;

        // Explicit simulation is useful for operators building a dry-run playbook.
        if (normalized.startsWith("simulate:") || normalized.startsWith("模拟:")) {
            return SIMULATED;
        }
        if (normalized.contains("http://") || normalized.contains("https://")) {
            return WEBHOOK;
        }
        if (normalized.contains("notify") || normalized.contains("通知")) {
            return NOTIFY;
        }
        if (normalized.contains("case") || normalized.contains("建案") || normalized.contains("事件单")) {
            return CASE;
        }
        if (normalized.contains("firewall-block") || normalized.contains("firewall_block")
                || (normalized.contains("firewall") && normalized.contains("block"))
                || normalized.contains("防火墙封禁")) {
            return FIREWALL_BLOCK;
        }
        if (normalized.contains("network-isolate") || normalized.contains("network_isolate")
                || normalized.contains("isolate") || normalized.contains("containment")
                || normalized.contains("网络隔离")) {
            return NETWORK_ISOLATE;
        }
        if (normalized.contains("snapshot") || normalized.contains("forensic")
                || normalized.contains("快照取证")) {
            return SNAPSHOT;
        }
        if ((normalized.contains("asset") && normalized.contains("lookup"))
                || normalized.contains("asset-lookup") || normalized.contains("asset_lookup")) {
            return ASSET_LOOKUP;
        }
        if (normalized.contains("tag") || normalized.contains("标签") || normalized.contains("标记")) {
            return TAG;
        }

        return UNKNOWN;
    }

    public static boolean compensation(String action) {
        if (action == null) return false;
        String value = action.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("compensate:") || value.startsWith("补偿:");
    }

    private static String stripCompensation(String action) {
        String value = action.trim();
        if (value.regionMatches(true, 0, "compensate:", 0, "compensate:".length())) {
            return value.substring("compensate:".length()).trim();
        }
        if (value.startsWith("补偿:")) {
            return value.substring("补偿:".length()).trim();
        }
        return value;
    }
}
