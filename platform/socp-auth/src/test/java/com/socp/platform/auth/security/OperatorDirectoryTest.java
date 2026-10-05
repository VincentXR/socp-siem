package com.socp.platform.auth.security;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperatorDirectoryTest {
    private final OperatorDirectory directory = new OperatorDirectory("""
        {"tenant-a":[{"id":"alice","label":"Alice","role":"analyst"},
                     {"id":"disabled","role":"admin","enabled":false},
                     {"id":"read-only","role":"viewer"}],
         "tenant-b":[{"id":"bob","role":"analyst"}]}
        """, "", "");

    @AfterEach void clear() { TenantContext.clear(); AuthenticatedIdentityContext.clear(); }

    @Test void directoryAndAssignmentShareTenantMembershipAndDisabledState() {
        assertEquals(List.of("alice"), directory.list("tenant-a", "read-only", "viewer").stream().map(OperatorDirectory.Operator::id).toList());
        TenantContext.set("tenant-a");
        assertDoesNotThrow(() -> directory.requireAssignable("alice"));
        assertThrows(ApiException.class, () -> directory.requireAssignable("bob"));
        assertThrows(ApiException.class, () -> directory.requireAssignable("disabled"));
        assertThrows(ApiException.class, () -> directory.requireAssignable("read-only"));
        assertThrows(ApiException.class, () -> directory.requireAssignable("unknown"));
        assertDoesNotThrow(() -> directory.requireAssignable(""));
    }

    @Test void selfAssignmentRequiresVerifiedSameTenantHumanIdentityAndCannotBypassDisabledMembership() {
        TenantContext.set("tenant-a");
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("oidc-user", "tenant-a", "analyst", Set.of(), Set.of(), AuthenticatedIdentity.Kind.USER));
        assertDoesNotThrow(() -> directory.requireAssignable("oidc-user"));
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("oidc-user", "tenant-b", "analyst", Set.of(), Set.of(), AuthenticatedIdentity.Kind.USER));
        assertThrows(ApiException.class, () -> directory.requireAssignable("oidc-user"));
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("disabled", "tenant-a", "admin", Set.of(), Set.of(), AuthenticatedIdentity.Kind.USER));
        assertThrows(ApiException.class, () -> directory.requireAssignable("disabled"));
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("service:soar-web", "tenant-a", "admin", Set.of(), Set.of(), AuthenticatedIdentity.Kind.SERVICE));
        assertThrows(ApiException.class, () -> directory.requireAssignable("service:soar-web"));
    }

    @Test void rejectsInvalidProvisioningAndNeverImportsLocalAccountsIntoOtherTenants() {
        assertThrows(IllegalArgumentException.class, () -> new OperatorDirectory("{\"tenant-a\":[{\"id\":\"x\",\"role\":\"superadmin\"}]}", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new OperatorDirectory("{\"tenant-a\":[{\"id\":\"x\"},{\"id\":\"x\"}]}", "", ""));
        OperatorDirectory local = new OperatorDirectory("{}", "{\"demo\":\"password\"}", "{\"demo\":\"analyst\"}");
        assertEquals(1, local.list("default", "demo", "analyst").size());
        assertTrue(local.list("tenant-a", "viewer", "viewer").isEmpty());
    }

    @Test void effectivePermissionsCombineKnownExplicitGrantsWithRoleDefaults() {
        assertEquals(Set.of("alarm:read", "soar:view", "soar:approve"), Permission.effective("viewer", List.of(" SOAR:APPROVE ", "unknown:root")));
        assertTrue(Permission.effective("analyst", "soar:publish,soar:approve").containsAll(Set.of("soar:publish", "soar:approve", "soar:edit")));
    }
}
