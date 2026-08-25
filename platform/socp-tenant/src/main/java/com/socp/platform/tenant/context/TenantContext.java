package com.socp.platform.tenant.context;
/**
 * 租户上下文：线程级持有当前租户 ID。整个请求链路（Controller→Service→DAO→Kafka→ES）
 * 都从它取 tenantId，配合各级前缀（Redis key / Kafka topic / ES index / AGE graph）实现隔离。
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final java.util.regex.Pattern VALID_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private TenantContext() {
    }

    public static void set(String tenantId) {
        if (!isValid(tenantId)) throw new IllegalArgumentException("Invalid tenant identifier");
        CURRENT.set(tenantId);
    }

    public static boolean isValid(String tenantId) {
        return tenantId != null && VALID_ID.matcher(tenantId).matches();
    }

    public static String get() {
        return CURRENT.get();
    }

    /** 取租户，缺失时抛异常——强制业务方必须带租户（SDK 级保证，见 §3.3） */
    public static String require() {
        String t = CURRENT.get();
        if (t == null || t.isBlank()) {
            throw new IllegalStateException("缺少租户上下文 (X-Tenant-Id)");
        }
        return t;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 租户前缀工具：Kafka topic / Redis key / ES index 统一加 {tenantId}- */
    public static String prefix(String name) {
        String t = get();
        return (t == null || t.isBlank()) ? name : t + "-" + name;
    }
}
