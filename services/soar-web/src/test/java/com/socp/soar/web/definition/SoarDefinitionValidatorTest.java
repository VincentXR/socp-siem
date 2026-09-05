package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoarDefinitionValidatorTest {
    private final SoarDefinitionValidator validator = new SoarDefinitionValidator(new ObjectMapper());

    @Test
    void acceptsNamespacedActionAndRejectsUnsafeExpression() {
        String valid = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]}";
        assertTrue(validator.validate(valid).valid());

        String unsafe = valid.replace("socp.notify/send@v1", "socp.notify/send@v1")
                .replace("\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\"",
                        "\"a\",\"type\":\"CONDITION\",\"expression\":\"java.lang.Runtime.exec()\"");
        assertFalse(validator.validate(unsafe).valid());
    }

    @Test
    void rejectsCyclesUnlessForeachIsPresent() {
        String cycle = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"s\"},{\"from\":\"s\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(cycle).valid());

        String reachableUnboundedCycle = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.alert/get@1\"},"
                + "{\"id\":\"b\",\"type\":\"ACTION\",\"actionRef\":\"socp.alert/get@1\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"b\",\"to\":\"a\"},{\"from\":\"b\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(reachableUnboundedCycle).valid());
    }

    @Test
    void enforcesPublishedBranchAndActionContracts() {
        String conditionWithoutPorts = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"c\",\"type\":\"CONDITION\",\"expression\":\"true\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"c\"},{\"from\":\"c\",\"port\":\"true\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(conditionWithoutPorts).valid());

        String documentedAction = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"endpoint/isolate-host@1\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]}";
        assertTrue(validator.validate(documentedAction).valid());
    }

    @Test
    void rejectsInlineActionSecrets() {
        String definition = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},"
                + "{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\","
                + "\"parameters\":{\"token\":\"do-not-store\"}},"
                + "{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(definition).valid());
    }

    @Test
    void rejectsMalformedBoundsAndDeadEndBranches() {
        String malformed = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"limits\":{\"maxNodeExecutions\":\"500\"},"
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.alert/get@1\",\"retry\":{\"maxAttempts\":1.5}},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(malformed).valid());

        String deadEnd = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.alert/get@1\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"}]}";
        assertFalse(validator.validate(deadEnd).valid());
    }

    @Test
    void rejectsUnknownPortsAndRequiresExplicitApprovalOutcomes() {
        String unknownActionPort = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\"," 
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.alert/get@1\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"port\":\"succes\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(unknownActionPort).valid());

        String incompleteApproval = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"APPROVAL\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"port\":\"approved\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(incompleteApproval).valid());
    }

    @Test
    void validatesApprovalRoleAndGroupAllowLists() {
        String base = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"APPROVAL\","
                + "\"policy\":{\"approvalsRequired\":1,\"allowedRoles\":[\"soc-approver\"],"
                + "\"allowedGroups\":[\"incident-command\"]}},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"port\":\"approved\",\"to\":\"e\"},{\"from\":\"a\",\"port\":\"rejected\",\"to\":\"e\"}]}";
        assertTrue(validator.validate(base).valid());

        String malformed = base.replace("[\"soc-approver\"]", "\"soc-approver\"");
        assertFalse(validator.validate(malformed).valid());
    }

    @Test
    void rejectsScalarControlPoliciesInsteadOfApplyingRuntimeDefaults() {
        String retryScalar = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"a\",\"type\":\"ACTION\","
                + "\"actionRef\":\"socp.alert/get@1\",\"retry\":\"unbounded\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]}";
        assertFalse(validator.validate(retryScalar).valid());

        String scalarLimits = retryScalar.replace("\"retry\":\"unbounded\"", "\"limits\":\"unbounded\"");
        assertFalse(validator.validate(scalarLimits).valid());
    }

    @Test
    void validatesRootApprovalPolicyAliasesAndPrincipalListsWithoutQuorumField() {
        String base = "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\","
                + "\"policy\":{\"allowedRoles\":[\"soc-approver\"]},"
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"e\"}]}";
        assertTrue(validator.validate(base).valid());

        String malformedList = base.replace("[\"soc-approver\"]", "\"soc-approver\"");
        assertFalse(validator.validate(malformedList).valid());

        String malformedPolicy = base.replace("\"policy\":{\"allowedRoles\":[\"soc-approver\"]}",
                "\"policy\":\"all\"");
        assertFalse(validator.validate(malformedPolicy).valid());
    }
}
