package com.socp.soar.web.service;

import com.socp.platform.auth.security.Permission;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;

/** Explicit identity fixture for service-level tests that bypass HTTP auth. */
final class SoarTestIdentity {
    private SoarTestIdentity() {
    }

    static void setOperator() {
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity(
                "operator", "tenant-a", "admin", Permission.roleDefaults("admin"),
                java.util.Set.of(), AuthenticatedIdentity.Kind.USER));
    }

    static void clear() {
        AuthenticatedIdentityContext.clear();
    }
}
