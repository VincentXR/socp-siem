package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the JWT permission vocabulary and per-role defaults in place. */
class PermissionCoverageTest {

    @Test
    void wireNamesUseColonsAndMatchTheDesignVocabulary() {
        assertThat(Permission.ALARM_READ.wireName()).isEqualTo("alarm:read");
        assertThat(Permission.ALARM_TRIAGE.wireName()).isEqualTo("alarm:triage");
        assertThat(Permission.CASE_WRITE.wireName()).isEqualTo("case:write");
        assertThat(Permission.RULE_ACTIVATE.wireName()).isEqualTo("rule:activate");
        assertThat(Permission.SOAR_VIEW.wireName()).isEqualTo("soar:view");
        assertThat(Permission.SOAR_EDIT.wireName()).isEqualTo("soar:edit");
        assertThat(Permission.SOAR_PUBLISH.wireName()).isEqualTo("soar:publish");
        assertThat(Permission.SOAR_EXECUTE.wireName()).isEqualTo("soar:execute");
        assertThat(Permission.SOAR_APPROVE.wireName()).isEqualTo("soar:approve");
        assertThat(Permission.SOAR_TASK_COMPLETE.wireName()).isEqualTo("soar:task:complete");
        assertThat(Permission.SOAR_CONNECTIONS_VIEW.wireName()).isEqualTo("soar:connections:view");
        assertThat(Permission.SOAR_CONNECTIONS_MANAGE.wireName()).isEqualTo("soar:connections:manage");
        assertThat(Permission.SOAR_OPERATIONS.wireName()).isEqualTo("soar:operations");
        assertThat(Permission.TENANT_ADMIN.wireName()).isEqualTo("tenant:admin");
    }

    @Test
    void adminReceivesEveryPermissionIncludingAllSoarGrants() {
        Set<String> admin = Permission.roleDefaults("admin");

        assertThat(admin).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(Permission.values()).map(Permission::wireName).collect(Collectors.toSet()));
        assertThat(admin).contains("soar:view", "soar:edit", "soar:publish", "soar:execute",
                "soar:approve", "soar:task:complete", "soar:connections:view",
                "soar:connections:manage", "soar:operations");
    }

    @Test
    void approverCanReadAlarmsApproveAndCompleteTasks() {
        assertThat(Permission.roleDefaults("approver")).containsExactlyInAnyOrder(
                "alarm:read", "soar:view", "soar:approve", "soar:task:complete");
    }

    @Test
    void analystCanTriageAndExecuteButNotPublishOrManageConnections() {
        assertThat(Permission.roleDefaults("analyst")).containsExactlyInAnyOrder(
                "alarm:read", "alarm:triage", "case:write", "soar:view", "soar:edit",
                "soar:execute", "soar:task:complete", "soar:connections:view");
        assertThat(Permission.roleDefaults("analyst"))
                .doesNotContain("soar:publish", "soar:operations", "soar:connections:manage");
    }

    @Test
    void viewerIsReadOnly() {
        assertThat(Permission.roleDefaults("viewer")).containsExactlyInAnyOrder(
                "alarm:read", "soar:view");
    }

    @Test
    void roleNamesAreCaseInsensitive() {
        assertThat(Permission.roleDefaults("ADMIN")).isEqualTo(Permission.roleDefaults("admin"));
        assertThat(Permission.roleDefaults("Analyst")).isEqualTo(Permission.roleDefaults("analyst"));
    }

    @Test
    void unknownOrMissingRolesGetNoPermissions() {
        assertThat(Permission.roleDefaults("ghost")).isEmpty();
        assertThat(Permission.roleDefaults(null)).isEmpty();
        assertThat(Permission.roleDefaults("")).isEmpty();
    }

    @Test
    void defaultsAreUnmodifiable() {
        Set<String> defaults = Permission.roleDefaults("viewer");
        assertThatThrownBy(() -> defaults.add("alarm:read"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Permission.roleDefaults("admin").remove("soar:view"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
