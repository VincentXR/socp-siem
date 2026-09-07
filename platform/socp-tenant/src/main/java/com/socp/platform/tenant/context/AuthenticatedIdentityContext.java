package com.socp.platform.tenant.context;

import java.util.Optional;
import java.util.function.Supplier;

/** Request-scoped trusted identity independent of Spring Security. */
public final class AuthenticatedIdentityContext {
    private static final ThreadLocal<AuthenticatedIdentity> CURRENT = new ThreadLocal<>();

    private AuthenticatedIdentityContext() {
    }

    public static void set(AuthenticatedIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        CURRENT.set(identity);
    }

    public static Optional<AuthenticatedIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static AuthenticatedIdentity require() {
        AuthenticatedIdentity identity = CURRENT.get();
        if (identity == null) throw new IllegalStateException("authenticated identity is not available");
        return identity;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Propagate a captured identity to bounded synchronous callbacks. */
    public static <T> T runWith(AuthenticatedIdentity identity, Supplier<T> callback) {
        AuthenticatedIdentity previous = CURRENT.get();
        try {
            if (identity == null) CURRENT.remove(); else CURRENT.set(identity);
            return callback.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void runWith(AuthenticatedIdentity identity, Runnable callback) {
        runWith(identity, () -> {
            callback.run();
            return null;
        });
    }
}
