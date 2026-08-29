package com.socp.platform.tenant.context;
/**
 * 租户上下文：线程级持有当前租户 ID。整个请求链路（Controller→Service→DAO→Kafka→ES）
 * 都从它取 tenantId，配合各级前缀（Redis key / Kafka topic / ES index / AGE graph）实现隔离。
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM_SCOPE = new ThreadLocal<>();
    private static final java.util.regex.Pattern VALID_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private TenantContext() {
    }

    public static void set(String tenantId) {
        if (!isValid(tenantId)) throw new IllegalArgumentException("Invalid tenant identifier");
        CURRENT.set(tenantId);
        SYSTEM_SCOPE.remove();
    }

    public static boolean isValid(String tenantId) {
        return tenantId != null && VALID_ID.matcher(tenantId).matches();
    }

    public static String get() {
        return CURRENT.get();
    }

    /** Returns true only for a bounded, explicitly requested system job. */
    public static boolean isSystemScope() {
        return Boolean.TRUE.equals(SYSTEM_SCOPE.get());
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
        SYSTEM_SCOPE.remove();
    }

    /** Installs a tenant for a bounded block and restores the previous value. */
    public static Scope open(String tenantId) {
        String previous = get();
        boolean previousSystem = isSystemScope();
        set(tenantId);
        return new Scope(previous, previousSystem);
    }

    /** Installs an explicit system scope for cross-tenant maintenance jobs. */
    public static Scope openSystem() {
        String previous = get();
        boolean previousSystem = isSystemScope();
        CURRENT.remove();
        SYSTEM_SCOPE.set(Boolean.TRUE);
        return new Scope(previous, previousSystem);
    }

    public static void runWith(String tenantId, Runnable action) {
        try (Scope ignored = open(tenantId)) {
            action.run();
        }
    }

    public static void runAsSystem(Runnable action) {
        try (Scope ignored = openSystem()) {
            action.run();
        }
    }

    public static <T> T callWith(String tenantId, java.util.function.Supplier<T> action) {
        try (Scope ignored = open(tenantId)) {
            return action.get();
        }
    }

    /** Captures the current tenant for execution on another thread. */
    public static Runnable wrap(Runnable action) {
        String captured = require();
        return () -> runWith(captured, action);
    }

    public static Runnable wrap(String tenantId, Runnable action) {
        return () -> runWith(tenantId, action);
    }

    public static <T> java.util.function.Supplier<T> wrap(
            String tenantId, java.util.function.Supplier<T> action) {
        return () -> callWith(tenantId, action);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private final boolean previousSystem;
        private boolean closed;

        private Scope(String previous, boolean previousSystem) {
            this.previous = previous;
            this.previousSystem = previousSystem;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove();
            else set(previous);
            if (previousSystem) SYSTEM_SCOPE.set(Boolean.TRUE);
            else SYSTEM_SCOPE.remove();
        }
    }

    /** 租户前缀工具：Kafka topic / Redis key / ES index 统一加 {tenantId}- */
    public static String prefix(String name) {
        String t = get();
        return (t == null || t.isBlank()) ? name : t + "-" + name;
    }
}
