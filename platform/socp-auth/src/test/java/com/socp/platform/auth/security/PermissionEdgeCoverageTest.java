package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional edge cases for {@link Permission#roleDefaults(String)}: the
 * approver and viewer grant sets, case-insensitive role matching and the
 * unmodifiable empty fallback.
 */
class PermissionEdgeCoverageTest {

    @Test
    void approverRoleYieldsTheApprovalOrientedDefaults() {
        assertThat(Permission.roleDefaults("approver")).containsExactlyInAnyOrder(
                "alarm:read", "soar:view", "soar:approve", "soar:task:complete");
        assertThat(Permission.roleDefaults("approver"))
                .doesNotContain("soar:edit", "soar:publish", "soar:execute", "soar:operations", "tenant:admin");
    }

    @Test
    void approverRoleIsMatchedCaseInsensitively() {
        assertThat(Permission.roleDefaults("APPROVER")).isEqualTo(Permission.roleDefaults("approver"));
        assertThat(Permission.roleDefaults("Approver")).isEqualTo(Permission.roleDefaults("approver"));
    }

    @Test
    void viewerRoleYieldsReadOnlyDefaults() {
        assertThat(Permission.roleDefaults("viewer")).containsExactlyInAnyOrder("alarm:read", "soar:view");
        assertThat(Permission.roleDefaults("VIEWER")).isEqualTo(Permission.roleDefaults("viewer"));
        assertThat(Permission.roleDefaults("Viewer")).isEqualTo(Permission.roleDefaults("viewer"));
    }

    @Test
    void analystRoleKeepsTriageAndExecutionWithoutPublishRights() {
        Set<String> analyst = Permission.roleDefaults("ANALYST");
        assertThat(analyst).contains("alarm:read", "alarm:triage", "case:write", "soar:view",
                "soar:edit", "soar:execute", "soar:task:complete", "soar:connections:view");
        assertThat(analyst).doesNotContain("soar:publish", "soar:approve", "soar:connections:manage",
                "soar:operations", "tenant:admin");
    }

    @Test
    void unknownRolesYieldAnEmptyImmutableSet() {
        assertThat(Permission.roleDefaults("ghost")).isEmpty();
        assertThat(Permission.roleDefaults(" ")).isEmpty();
        Set<String> fallback = Permission.roleDefaults(null);
        assertThat(fallback).isEmpty();
        assertThatThrownBy(() -> fallback.add("alarm:read"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
