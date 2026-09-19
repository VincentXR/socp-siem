package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional edge cases for {@link Permission#roleDefaults(String)}: the
 * issuable role vocabulary, case-insensitive role matching and the
 * unmodifiable empty fallback.
 */
class PermissionEdgeCoverageTest {

    @Test
    void roleWithoutAnIssuancePathYieldsNoDefaults() {
        // approver was documented as a SOAR reviewer but no delivered component
        // can issue that role, so it must behave like any other unknown role.
        assertThat(Permission.roleDefaults("approver")).isEmpty();
        assertThat(Permission.roleDefaults("operator")).isEmpty();
        assertThat(Permission.ISSUABLE_ROLES).doesNotContain("approver", "operator");
    }

    @Test
    void issuableRolesAreTheOnlyRolesThePlatformGrantsDefaultsFor() {
        assertThat(Permission.ISSUABLE_ROLES).containsExactlyInAnyOrder("admin", "analyst", "viewer");
        for (String issuable : Permission.ISSUABLE_ROLES) {
            assertThat(Permission.roleDefaults(issuable)).isNotEmpty();
        }
    }

    @Test
    void roleMatchingIsCaseInsensitive() {
        assertThat(Permission.roleDefaults("ANALYST")).isEqualTo(Permission.roleDefaults("analyst"));
        assertThat(Permission.roleDefaults("Viewer")).isEqualTo(Permission.roleDefaults("viewer"));
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
