package com.socp.soar.web.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoarDomainModelCoverageTest {

    @Test
    void nodeTypeParseIsCaseInsensitiveAndNullSafe() {
        assertThat(SoarNodeType.parse(null)).isNull();
        assertThat(SoarNodeType.parse("   ")).isNull();
        assertThat(SoarNodeType.parse("bogus")).isNull();
        assertThat(SoarNodeType.parse("action")).isEqualTo(SoarNodeType.ACTION);
        assertThat(SoarNodeType.parse(" Manual_Task ")).isEqualTo(SoarNodeType.MANUAL_TASK);
        assertThat(SoarNodeType.parse("START")).isEqualTo(SoarNodeType.START);

        assertThat(SoarNodeType.values()).hasSize(13);
        assertThat(SoarNodeType.valueOf("SET_VARIABLE")).isEqualTo(SoarNodeType.SET_VARIABLE);
    }

    @Test
    void runStatusEnumExposesLifecycleConstants() {
        assertThat(SoarRunStatus.values()).hasSize(14);
        assertThat(SoarRunStatus.valueOf("QUEUED")).isEqualTo(SoarRunStatus.QUEUED);
        assertThat(SoarRunStatus.valueOf("WAITING_APPROVAL")).isEqualTo(SoarRunStatus.WAITING_APPROVAL);
        assertThat(SoarRunStatus.valueOf("SUCCEEDED")).isEqualTo(SoarRunStatus.SUCCEEDED);
        assertThat(SoarRunStatus.valueOf("PARTIALLY_SUCCEEDED")).isEqualTo(SoarRunStatus.PARTIALLY_SUCCEEDED);
        assertThat(SoarRunStatus.valueOf("CANCELLED")).isEqualTo(SoarRunStatus.CANCELLED);
        assertThat(SoarRunStatus.valueOf("DEAD")).isEqualTo(SoarRunStatus.DEAD);
        assertThatThrownBy(() -> SoarRunStatus.valueOf("NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void playbookVersionStatusEnumExposesPublicationConstants() {
        assertThat(SoarPlaybookVersionStatus.values()).containsExactly(
                SoarPlaybookVersionStatus.DRAFT,
                SoarPlaybookVersionStatus.PUBLISHED,
                SoarPlaybookVersionStatus.DEPRECATED,
                SoarPlaybookVersionStatus.DELETED);
    }

    @Test
    void definitionIssueFactoriesSetSeverityAndAccessors() {
        DefinitionIssue error = DefinitionIssue.error("SOME_CODE", "node-1", "/nodes/0", "something failed");
        assertThat(error.severity()).isEqualTo("ERROR");
        assertThat(error.code()).isEqualTo("SOME_CODE");
        assertThat(error.nodeId()).isEqualTo("node-1");
        assertThat(error.path()).isEqualTo("/nodes/0");
        assertThat(error.message()).isEqualTo("something failed");

        DefinitionIssue warning = DefinitionIssue.warning("WARN_CODE", null, "/nodes", "be careful");
        assertThat(warning.severity()).isEqualTo("WARNING");
        assertThat(warning.code()).isEqualTo("WARN_CODE");
        assertThat(warning.nodeId()).isNull();
        assertThat(warning.path()).isEqualTo("/nodes");
        assertThat(warning.message()).isEqualTo("be careful");
    }

    @Test
    void validationResultDefaultsNullListsToEmptyAndDefensivelyCopies() {
        DefinitionValidationResult withNulls = new DefinitionValidationResult(
                false, null, null, "soar.playbook", "hash-1", 0, 0, 0);
        assertThat(withNulls.valid()).isFalse();
        assertThat(withNulls.errors()).isEmpty();
        assertThat(withNulls.warnings()).isEmpty();
        assertThat(withNulls.schemaVersion()).isEqualTo("soar.playbook");
        assertThat(withNulls.definitionHash()).isEqualTo("hash-1");
        assertThat(withNulls.nodeCount()).isZero();
        assertThat(withNulls.actionCount()).isZero();
        assertThat(withNulls.highRiskActionCount()).isZero();

        List<DefinitionIssue> mutable = new ArrayList<>();
        mutable.add(DefinitionIssue.error("E1", null, "", "first"));
        DefinitionValidationResult result = new DefinitionValidationResult(
                false, mutable, List.of(), null, null, 1, 1, 0);
        mutable.add(DefinitionIssue.error("E2", null, "", "second"));
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo("E1");
        assertThatThrownBy(() -> result.errors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
