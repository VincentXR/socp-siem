package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.ActionDescriptor;
import com.socp.soar.web.connector.ConnectorDescriptor;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.domain.v2.DefinitionIssue;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Branch coverage for the structural validator: each test trips one specific
 * error or warning code using a minimal, hermetic definition document.
 */
@ExtendWith(MockitoExtension.class)
class SoarDefinitionValidatorBranchesCoverageTest {

    private static final String START = "{\"id\":\"s\",\"type\":\"START\"}";
    private static final String ACTION = "{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\"}";
    private static final String END = "{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}";
    private static final String SE_NODES = "[" + START + "," + ACTION + "," + END + "]";
    private static final String SE_EDGES = "[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]";
    private static final String FOREACH_EDGES = "[{\"from\":\"s\",\"to\":\"f\"},"
            + "{\"from\":\"f\",\"port\":\"body\",\"to\":\"a\"},"
            + "{\"from\":\"f\",\"port\":\"done\",\"to\":\"e\"},{\"from\":\"a\",\"to\":\"e\"}]";
    private static final String APPROVAL_EDGES = "[{\"from\":\"s\",\"to\":\"ap\"},"
            + "{\"from\":\"ap\",\"port\":\"approved\",\"to\":\"e\"},"
            + "{\"from\":\"ap\",\"port\":\"rejected\",\"to\":\"e\"}]";

    private SoarDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        validator = new SoarDefinitionValidator(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static String definition(String nodes, String edges) {
        return "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\",\"nodes\":" + nodes
                + ",\"edges\":" + edges + "}";
    }

    private static String definition(String limits, String nodes, String edges) {
        return "{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\",\"limits\":" + limits
                + ",\"nodes\":" + nodes + ",\"edges\":" + edges + "}";
    }

    private static String withRootApprovalPolicy(String policyJson, String nodes, String edges) {
        return definition(nodes, edges).replace("\"nodes\":",
                "\"approvalPolicy\":" + policyJson + ",\"nodes\":");
    }

    /** FOREACH graph; {@code extras} are appended inside the FOREACH node JSON. */
    private static String foreachGraph(String foreachNodeExtras, String edges) {
        String f = "{\"id\":\"f\",\"type\":\"FOREACH\",\"config\":{\"itemsPath\":\"$.items\"}"
                + foreachNodeExtras + "}";
        return definition("[" + START + "," + f + "," + ACTION + "," + END + "]", edges);
    }

    /** PARALLEL graph with two branches; {@code extras} appended inside the node JSON. */
    private static String parallelGraph(String parallelNodeExtras) {
        String p = "{\"id\":\"p\",\"type\":\"PARALLEL\"" + parallelNodeExtras + "}";
        String nodes = "[" + START + "," + p + "," + ACTION + ",{\"id\":\"b\",\"type\":\"ACTION\","
                + "\"actionRef\":\"socp.notify/send@v1\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"p\"},{\"from\":\"p\",\"to\":\"a\"},"
                + "{\"from\":\"p\",\"to\":\"b\"},{\"from\":\"a\",\"to\":\"e\"},{\"from\":\"b\",\"to\":\"e\"}]";
        return definition(nodes, edges);
    }

    /** MANUAL_TASK graph; {@code extras} appended inside the node JSON. */
    private static String manualTaskDef(String manualNodeExtras) {
        String m = "{\"id\":\"m\",\"type\":\"MANUAL_TASK\"" + manualNodeExtras + "}";
        return definition("[" + START + "," + m + "," + END + "]",
                "[{\"from\":\"s\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"e\"}]");
    }

    private static String repeatedQuotedValues(String prefix, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(prefix).append(i).append('"');
        }
        return sb.append(']').toString();
    }

    /** Valid JSON nesting {@code levels} "properties" wrappers deep. */
    private static String deepFormSchema(int levels) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{\"p\":");
        for (int i = 1; i < levels; i++) sb.append("{\"properties\":{\"p\":");
        // The leaf is the innermost wrapper's "p" value; each wrapper then
        // closes with its "p" value object, and the final pair closes the
        // outer properties object and the root.
        sb.append("{\"type\":\"string\"}");
        for (int i = 1; i < levels; i++) sb.append("}}");
        sb.append("}}");
        return sb.toString();
    }

    private void assertHasError(String document, String code) {
        DefinitionValidationResult result = validator.validate(document);
        assertThat(result.errors()).as("expected error %s but got %s", code, result.errors())
                .anyMatch(issue -> code.equals(issue.code()));
    }

    private void assertNoError(String document, String code) {
        DefinitionValidationResult result = validator.validate(document);
        assertThat(result.errors()).as("unexpected error in %s", result.errors())
                .noneMatch(issue -> code.equals(issue.code()));
    }

    private void assertHasWarning(String document, String code) {
        DefinitionValidationResult result = validator.validate(document);
        assertThat(result.warnings()).as("expected warning %s but got %s", code, result.warnings())
                .anyMatch(issue -> code.equals(issue.code()));
    }

    private void assertNoWarning(String document, String code) {
        DefinitionValidationResult result = validator.validate(document);
        assertThat(result.warnings()).noneMatch(issue -> code.equals(issue.code()));
    }

    // ------------------------------------------------------------ document level

    @Test
    void requiresDefinitionWhenMissingOrBlank() {
        assertThat(validator.validate(null).valid()).isFalse();
        assertThat(validator.validate(null).errors())
                .anyMatch(issue -> "DEFINITION_REQUIRED".equals(issue.code()));
        assertThat(validator.validate("   ").errors())
                .anyMatch(issue -> "DEFINITION_REQUIRED".equals(issue.code()));
    }

    @Test
    void rejectsOversizedDefinition() {
        String huge = "{\"schemaVersion\":\"soar.playbook/v2\",\"pad\":\"" + "x".repeat(300_000) + "\"}";
        assertHasError(huge, "DEFINITION_TOO_LARGE");
    }

    @Test
    void rejectsNonJsonDefinition() {
        assertHasError("{nope", "DEFINITION_NOT_JSON");
    }

    @Test
    void rejectsNonObjectRoot() {
        assertHasError("[]", "DEFINITION_NOT_OBJECT");
        assertHasError("\"just text\"", "DEFINITION_NOT_OBJECT");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertHasError(definition(SE_NODES, SE_EDGES).replace("soar.playbook/v2", "soar.playbook/v1"),
                "UNSUPPORTED_SCHEMA_VERSION");
    }

    @Test
    void requiresEntryNodeId() {
        assertHasError(definition(SE_NODES, SE_EDGES).replace("\"entryNodeId\":\"s\",", ""),
                "ENTRY_REQUIRED");
    }

    @Test
    void requiresNonEmptyNodeArray() {
        assertHasError("{\"schemaVersion\":\"soar.playbook/v2\",\"entryNodeId\":\"s\",\"edges\":[]}",
                "NODES_REQUIRED");
        assertHasError(definition("[]", "[]"), "NODES_REQUIRED");
        assertHasError(definition("{}", "[]"), "NODES_REQUIRED");
    }

    @Test
    void rejectsInlineRootSecrets() {
        String nodes = "[{\"id\":\"s\",\"type\":\"START\",\"config\":{\"password\":\"hunter2\"}},"
                + ACTION + "," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "DEFINITION_SECRET_INLINE_FORBIDDEN");
    }

    @Test
    void rejectsScalarRootLimits() {
        String withScalarLimits = definition(SE_NODES, SE_EDGES)
                .replace("\"nodes\":", "\"limits\":\"fast\",\"nodes\":");
        assertHasError(withScalarLimits, "DEFINITION_LIMITS_INVALID");
        assertHasError(withScalarLimits, "LIMITS_INVALID");
    }

    @Test
    void rejectsRootApprovalQuorumOutOfRange() {
        assertHasError(withRootApprovalPolicy("{\"approvalsRequired\":25}", SE_NODES, SE_EDGES),
                "APPROVAL_POLICY_INVALID");
        assertHasError(withRootApprovalPolicy("{\"requiredApprovals\":0}", SE_NODES, SE_EDGES),
                "APPROVAL_POLICY_INVALID");
        assertHasError(withRootApprovalPolicy("{\"approvalsRequired\":\"2\"}", SE_NODES, SE_EDGES),
                "APPROVAL_POLICY_INVALID");
    }

    @Test
    void rejectsRootApprovalPrincipalListWithBlankValue() {
        assertHasError(withRootApprovalPolicy("{\"allowedRoles\":[\"\"]}", SE_NODES, SE_EDGES),
                "APPROVAL_POLICY_INVALID");
        assertHasError(withRootApprovalPolicy("{\"allowedGroups\":[\"ok\",\"   \"]}", SE_NODES, SE_EDGES),
                "APPROVAL_POLICY_INVALID");
    }

    @Test
    void rejectsRootApprovalPrincipalListWithTooManyValues() {
        String roles = repeatedQuotedValues("role-", 65);
        assertHasError(withRootApprovalPolicy("{\"allowedRoles\":" + roles + "}", SE_NODES, SE_EDGES),
                "APPROVAL_POLICY_INVALID");
    }

    @Test
    void rejectsTooManyNodes() {
        StringBuilder nodes = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) nodes.append(',');
            nodes.append("{\"id\":\"n").append(i).append("\",\"type\":\"DELAY\"}");
        }
        nodes.append(']');
        assertHasError(definition(nodes.toString(), "[]"), "TOO_MANY_NODES");
    }

    @Test
    void requiresEdgeArray() {
        assertHasError(definition(SE_NODES, "\"broken\""), "EDGES_REQUIRED");
        assertHasError(definition(SE_NODES, "{}"), "EDGES_REQUIRED");
    }

    // ---------------------------------------------------------------- node level

    @Test
    void rejectsNonObjectNode() {
        assertHasError(definition("[5]", "[]"), "NODE_NOT_OBJECT");
    }

    @Test
    void rejectsInvalidNodeId() {
        String nodes = "[" + START.replace("\"id\":\"s\"", "\"id\":\"1bad\"") + "," + ACTION + "," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "NODE_ID_INVALID");
        String longId = "{\"id\":\"" + "a".repeat(65) + "\",\"type\":\"START\"}";
        assertHasError(definition("[" + longId + "," + ACTION + "," + END + "]", SE_EDGES),
                "NODE_ID_INVALID");
    }

    @Test
    void rejectsUnknownNodeType() {
        String nodes = "[" + START.replace("\"type\":\"START\"", "\"type\":\"MAGIC\"")
                + "," + ACTION + "," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "NODE_TYPE_INVALID");
    }

    @Test
    void rejectsScalarNodeConfig() {
        String nodes = "[{\"id\":\"s\",\"type\":\"START\",\"config\":\"fast\"}," + ACTION + "," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "NODE_CONFIG_INVALID");
    }

    @Test
    void requiresActionRef() {
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\"}," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "ACTION_REF_REQUIRED");
    }

    @Test
    void rejectsNonNamespacedActionRef() {
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"notify\"},"
                + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "ACTION_REF_FORMAT_INVALID");
    }

    @Test
    void rejectsUnknownActionRef() {
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"ghost/action\"},"
                + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "ACTION_REF_UNKNOWN");
    }

    // ------------------------------------------------------------ ACTION retry

    @Test
    void rejectsNonIntegerMaximumAttempts() {
        String actionWithRetry = ACTION.substring(0, ACTION.length() - 1)
                + ",\"retry\":{\"maximumAttempts\":\"many\"}}";
        assertHasError(definition("[" + START + "," + actionWithRetry + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_LIMIT_INVALID");
    }

    @Test
    void rejectsNonIntegerBackoffSeconds() {
        String actionWithRetry = ACTION.substring(0, ACTION.length() - 1)
                + ",\"retry\":{\"maxAttempts\":1,\"backoffSeconds\":\"soon\"}}";
        assertHasError(definition("[" + START + "," + actionWithRetry + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_BACKOFF_INVALID");
    }

    @Test
    void rejectsInvalidInitialIntervalAndAcceptsValid() {
        String invalid = ACTION.substring(0, ACTION.length() - 1)
                + ",\"retry\":{\"maxAttempts\":1,\"initialInterval\":\"garbage\"}}";
        assertHasError(definition("[" + START + "," + invalid + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_BACKOFF_INVALID");

        String valid = ACTION.substring(0, ACTION.length() - 1)
                + ",\"retry\":{\"maxAttempts\":1,\"initialInterval\":\"PT2S\"}}";
        String document = definition("[" + START + "," + valid + "," + END + "]", SE_EDGES);
        assertNoError(document, "ACTION_RETRY_BACKOFF_INVALID");
        assertNoError(document, "ACTION_RETRY_LIMIT_INVALID");
    }

    @Test
    void rejectsMaxAttemptsOutOfRange() {
        String zero = ACTION.substring(0, ACTION.length() - 1) + ",\"retry\":{\"maxAttempts\":0}}";
        assertHasError(definition("[" + START + "," + zero + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_LIMIT_INVALID");
        String eleven = ACTION.substring(0, ACTION.length() - 1) + ",\"retry\":{\"maxAttempts\":11}}";
        assertHasError(definition("[" + START + "," + eleven + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_LIMIT_INVALID");
    }

    @Test
    void rejectsBackoffSecondsOutOfRange() {
        String high = ACTION.substring(0, ACTION.length() - 1)
                + ",\"retry\":{\"maxAttempts\":1,\"backoffSeconds\":400}}";
        assertHasError(definition("[" + START + "," + high + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_BACKOFF_INVALID");
        String negative = ACTION.substring(0, ACTION.length() - 1)
                + ",\"retry\":{\"maxAttempts\":1,\"backoffSeconds\":-1}}";
        assertHasError(definition("[" + START + "," + negative + "," + END + "]", SE_EDGES),
                "ACTION_RETRY_BACKOFF_INVALID");
    }

    // -------------------------------------------------------- onError / expression

    @Test
    void rejectsUnknownOnErrorValue() {
        String topLevel = ACTION.substring(0, ACTION.length() - 1) + ",\"onError\":\"RETRY_NOW\"}";
        assertHasError(definition("[" + START + "," + topLevel + "," + END + "]", SE_EDGES),
                "NODE_ON_ERROR_INVALID");
        String inConfig = ACTION.substring(0, ACTION.length() - 1)
                + ",\"config\":{\"onError\":\"WHAT\"}}";
        assertHasError(definition("[" + START + "," + inConfig + "," + END + "]", SE_EDGES),
                "NODE_ON_ERROR_INVALID");
    }

    @Test
    void requiresConditionExpression() {
        String nodes = "[" + START + ",{\"id\":\"c\",\"type\":\"CONDITION\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"c\"},{\"from\":\"c\",\"port\":\"true\",\"to\":\"e\"},"
                + "{\"from\":\"c\",\"port\":\"false\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "CONDITION_EXPRESSION_REQUIRED");
    }

    @Test
    void requiresSwitchExpression() {
        String nodes = "[" + START + ",{\"id\":\"sw\",\"type\":\"SWITCH\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"sw\"},{\"from\":\"sw\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "SWITCH_EXPRESSION_REQUIRED");
    }

    @Test
    void requiresEndOutcome() {
        String nodes = "[" + START + "," + ACTION + ",{\"id\":\"e\",\"type\":\"END\"}]";
        assertHasError(definition(nodes, SE_EDGES), "END_OUTCOME_REQUIRED");
    }

    @Test
    void rejectsUnsupportedEndOutcome() {
        String nodes = "[" + START + "," + ACTION + ","
                + "{\"id\":\"e\",\"type\":\"END\",\"outcome\":\"MAYBE\"}]";
        assertHasError(definition(nodes, SE_EDGES), "END_OUTCOME_INVALID");
    }

    // -------------------------------------------------------------- FOREACH nodes

    @Test
    void rejectsNonIntegerForeachMaxItems() {
        assertHasError(foreachGraph(",\"limits\":{\"maxItems\":\"many\"}", FOREACH_EDGES),
                "FOREACH_LIMIT_INVALID");
    }

    @Test
    void rejectsForeachMaxItemsOutOfRange() {
        assertHasError(foreachGraph(",\"limits\":{\"maxItems\":101}", FOREACH_EDGES),
                "FOREACH_LIMIT_INVALID");
        assertHasError(foreachGraph(",\"limits\":{\"maxItems\":0}", FOREACH_EDGES),
                "FOREACH_LIMIT_INVALID");
    }

    @Test
    void rejectsNonIntegerForeachConcurrency() {
        assertHasError(foreachGraph(",\"limits\":{\"maxItems\":10,\"concurrency\":\"many\"}", FOREACH_EDGES),
                "FOREACH_CONCURRENCY_INVALID");
    }

    @Test
    void rejectsForeachConcurrencyOutOfRange() {
        assertHasError(foreachGraph(",\"limits\":{\"maxItems\":10,\"concurrency\":11}", FOREACH_EDGES),
                "FOREACH_CONCURRENCY_INVALID");
        assertHasError(foreachGraph(",\"limits\":{\"maxItems\":10,\"concurrency\":0}", FOREACH_EDGES),
                "FOREACH_CONCURRENCY_INVALID");
    }

    @Test
    void requiresForeachItemsPath() {
        String nodes = "[" + START + ",{\"id\":\"f\",\"type\":\"FOREACH\"}," + ACTION + "," + END + "]";
        assertHasError(definition(nodes, FOREACH_EDGES), "FOREACH_ITEMS_REQUIRED");
    }

    @Test
    void rejectsNonVarsForeachItemVariable() {
        assertHasError(foreachGraph(",\"config\":{\"itemVariable\":\"item\"}", FOREACH_EDGES),
                "FOREACH_VARIABLE_SCOPE_INVALID");
    }

    @Test
    void requiresForeachBodyAndDonePorts() {
        String edges = "[{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"port\":\"body\",\"to\":\"a\"},"
                + "{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(foreachGraph("", edges), "FOREACH_PORTS_REQUIRED");
    }

    // ------------------------------------------------------------- PARALLEL / JOIN

    @Test
    void rejectsNonIntegerParallelMaxParallelism() {
        assertHasError(parallelGraph(",\"limits\":{\"maxParallelism\":\"many\"}"),
                "PARALLELISM_LIMIT_INVALID");
    }

    @Test
    void rejectsParallelMaxParallelismOutOfRange() {
        assertHasError(parallelGraph(",\"limits\":{\"maxParallelism\":11}"), "PARALLELISM_LIMIT_INVALID");
        assertHasError(parallelGraph(",\"limits\":{\"maxParallelism\":0}"), "PARALLELISM_LIMIT_INVALID");
    }

    @Test
    void requiresTwoParallelBranches() {
        String nodes = "[" + START + ",{\"id\":\"p\",\"type\":\"PARALLEL\"}," + ACTION + "," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"p\"},{\"from\":\"p\",\"to\":\"a\"},"
                + "{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "PARALLEL_BRANCHES_REQUIRED");
    }

    @Test
    void rejectsUnknownJoinStrategy() {
        String nodes = "[" + START + ",{\"id\":\"a1\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\"},"
                + "{\"id\":\"j\",\"type\":\"JOIN\",\"strategy\":\"SOMETIMES\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"a1\"},{\"from\":\"a1\",\"port\":\"success\",\"to\":\"j\"},"
                + "{\"from\":\"a1\",\"port\":\"failure\",\"to\":\"j\"},{\"from\":\"j\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "JOIN_STRATEGY_INVALID");
    }

    @Test
    void requiresTwoJoinBranches() {
        String nodes = "[" + START + ",{\"id\":\"a1\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\"},"
                + "{\"id\":\"j\",\"type\":\"JOIN\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"a1\"},{\"from\":\"a1\",\"to\":\"j\"},"
                + "{\"from\":\"j\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "JOIN_BRANCHES_REQUIRED");
    }

    // -------------------------------------------------------- APPROVAL / MANUAL / DELAY

    @Test
    void rejectsScalarApprovalNodePolicy() {
        String nodes = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\",\"policy\":\"auto\"}," + END + "]";
        assertHasError(definition(nodes, APPROVAL_EDGES), "APPROVAL_POLICY_INVALID");
    }

    @Test
    void rejectsApprovalNodeQuorumAndPrincipalLists() {
        String quorum = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\","
                + "\"policy\":{\"approvalsRequired\":0}}," + END + "]";
        assertHasError(definition(quorum, APPROVAL_EDGES), "APPROVAL_POLICY_INVALID");

        String emptyRoles = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\","
                + "\"policy\":{\"allowedRoles\":[]}}," + END + "]";
        assertHasError(definition(emptyRoles, APPROVAL_EDGES), "APPROVAL_POLICY_INVALID");

        String blankRole = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\","
                + "\"policy\":{\"approverGroups\":[\"\"]}}," + END + "]";
        assertHasError(definition(blankRole, APPROVAL_EDGES), "APPROVAL_POLICY_INVALID");
    }

    @Test
    void requiresApprovalApprovedPort() {
        String nodes = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"ap\"},{\"from\":\"ap\",\"port\":\"rejected\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "APPROVAL_APPROVED_PORT_REQUIRED");
    }

    @Test
    void rejectsManualTaskTimeoutNonInteger() {
        assertHasError(manualTaskDef(",\"config\":{\"timeoutSeconds\":\"soon\"}"),
                "MANUAL_TASK_TIMEOUT_INVALID");
    }

    @Test
    void rejectsManualTaskTimeoutOutOfRange() {
        assertHasError(manualTaskDef(",\"config\":{\"timeoutSeconds\":3000000}"),
                "MANUAL_TASK_TIMEOUT_INVALID");
    }

    @Test
    void rejectsApprovalTimeoutOutOfRange() {
        String nodes = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\","
                + "\"config\":{\"timeoutSeconds\":-1}}," + END + "]";
        assertHasError(definition(nodes, APPROVAL_EDGES), "APPROVAL_TIMEOUT_INVALID");
    }

    @Test
    void rejectsNonIntegerDelayDuration() {
        String nodes = "[" + START + ",{\"id\":\"d\",\"type\":\"DELAY\",\"durationSeconds\":\"soon\"},"
                + END + "]";
        assertHasError(definition(nodes, "[{\"from\":\"s\",\"to\":\"d\"},{\"from\":\"d\",\"to\":\"e\"}]"),
                "DELAY_DURATION_INVALID");
    }

    @Test
    void rejectsDelayDurationOutOfRange() {
        String nodes = "[" + START + ",{\"id\":\"d\",\"type\":\"DELAY\",\"durationSeconds\":90000},"
                + END + "]";
        assertHasError(definition(nodes, "[{\"from\":\"s\",\"to\":\"d\"},{\"from\":\"d\",\"to\":\"e\"}]"),
                "DELAY_DURATION_INVALID");
    }

    @Test
    void delayWithoutDurationIsAccepted() {
        String nodes = "[" + START + ",{\"id\":\"d\",\"type\":\"DELAY\"}," + END + "]";
        String document = definition(nodes, "[{\"from\":\"s\",\"to\":\"d\"},{\"from\":\"d\",\"to\":\"e\"}]");
        assertNoError(document, "DELAY_DURATION_INVALID");
    }

    // ------------------------------------------------------- SET_VARIABLE / SUB_PLAYBOOK

    @Test
    void requiresSetVariableNameAndValue() {
        String nodes = "[" + START + ",{\"id\":\"v\",\"type\":\"SET_VARIABLE\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"v\"},{\"from\":\"v\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "VARIABLE_NAME_REQUIRED");
        assertHasError(definition(nodes, edges), "VARIABLE_VALUE_REQUIRED");
    }

    @Test
    void rejectsNonVarsSetVariableName() {
        String nodes = "[" + START + ",{\"id\":\"v\",\"type\":\"SET_VARIABLE\","
                + "\"config\":{\"name\":\"global\",\"value\":\"x\"}}," + END + "]";
        assertHasError(definition(nodes, "[{\"from\":\"s\",\"to\":\"v\"},{\"from\":\"v\",\"to\":\"e\"}]"),
                "VARIABLE_SCOPE_INVALID");
    }

    @Test
    void requiresSubPlaybookReference() {
        String nodes = "[" + START + ",{\"id\":\"sp\",\"type\":\"SUB_PLAYBOOK\"}," + END + "]";
        assertHasError(definition(nodes, "[{\"from\":\"s\",\"to\":\"sp\"},{\"from\":\"sp\",\"to\":\"e\"}]"),
                "SUB_PLAYBOOK_REFERENCE_REQUIRED");
    }

    // --------------------------------------------------------------- graph shape

    @Test
    void requiresExactlyOneStart() {
        String nodes = "[" + ACTION + "," + END + "]";
        assertHasError(definition(nodes, "[{\"from\":\"a\",\"to\":\"e\"}]"), "START_COUNT_INVALID");
    }

    @Test
    void requiresAtLeastOneEnd() {
        String nodes = "[" + START + "," + ACTION + "]";
        assertHasError(definition(nodes, "[{\"from\":\"s\",\"to\":\"a\"}]"), "END_REQUIRED");
    }

    @Test
    void rejectsUnknownEntryNode() {
        assertHasError(definition(SE_NODES, SE_EDGES).replace("\"entryNodeId\":\"s\"", "\"entryNodeId\":\"zz\""),
                "ENTRY_NOT_FOUND");
    }

    @Test
    void rejectsNonStartEntryNode() {
        assertHasError(definition(SE_NODES, SE_EDGES).replace("\"entryNodeId\":\"s\"", "\"entryNodeId\":\"a\""),
                "ENTRY_NOT_START");
    }

    @Test
    void rejectsEdgeWithUnknownSource() {
        String edges = "[{\"from\":\"zz\",\"to\":\"a\"},{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(definition(SE_NODES, edges), "EDGE_SOURCE_NOT_FOUND");
    }

    @Test
    void rejectsEdgeWithUnknownTarget() {
        String edges = "[{\"from\":\"s\",\"to\":\"zz\"},{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(definition(SE_NODES, edges), "EDGE_TARGET_NOT_FOUND");
    }

    @Test
    void requiresEdgeEndpoints() {
        String edges = "[{\"from\":\"\",\"to\":\"a\"},{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(definition(SE_NODES, edges), "EDGE_ENDPOINT_REQUIRED");
    }

    @Test
    void rejectsMalformedEdgePort() {
        String edges = "[{\"from\":\"s\",\"port\":\"9bad\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(definition(SE_NODES, edges), "EDGE_PORT_INVALID");
    }

    @Test
    void rejectsDuplicateEdges() {
        String edges = "[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(definition(SE_NODES, edges), "EDGE_DUPLICATE");
    }

    @Test
    void rejectsEndWithOutgoingEdge() {
        String edges = "[{\"from\":\"s\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"e\"},{\"from\":\"e\",\"to\":\"a\"}]";
        assertHasError(definition(SE_NODES, edges), "END_HAS_OUTGOING_EDGE");
    }

    // ------------------------------------------------------------- branch ports

    @Test
    void requiresSwitchDefaultPort() {
        String nodes = "[" + START + ",{\"id\":\"sw\",\"type\":\"SWITCH\",\"expression\":\"true\","
                + "\"cases\":[{\"port\":\"one\"}]}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"sw\"},{\"from\":\"sw\",\"port\":\"one\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "SWITCH_DEFAULT_REQUIRED");
    }

    @Test
    void rejectsUndeclaredSwitchPort() {
        String nodes = "[" + START + ",{\"id\":\"sw\",\"type\":\"SWITCH\",\"expression\":\"true\","
                + "\"cases\":[{\"port\":\"one\"}]},{\"id\":\"a1\",\"type\":\"ACTION\","
                + "\"actionRef\":\"socp.notify/send@v1\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"sw\"},{\"from\":\"sw\",\"port\":\"one\",\"to\":\"a1\"},"
                + "{\"from\":\"sw\",\"port\":\"two\",\"to\":\"e\"},{\"from\":\"a1\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "EDGE_PORT_NOT_ALLOWED");
    }

    @Test
    void acceptsDeclaredSwitchPorts() {
        String switchNode = "{\"id\":\"sw\",\"type\":\"SWITCH\",\"expression\":\"true\","
                + "\"cases\":[{\"port\":\"one\"}]}";
        String nodes = "[" + START + "," + switchNode + "," + ACTION + "," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"sw\"},{\"from\":\"sw\",\"port\":\"one\",\"to\":\"a\"},"
                + "{\"from\":\"sw\",\"to\":\"e\"},{\"from\":\"a\",\"to\":\"e\"}]";
        assertThat(validator.validate(definition(nodes, edges)).valid()).isTrue();

        String configCases = "{\"id\":\"sw\",\"type\":\"SWITCH\",\"expression\":\"true\","
                + "\"config\":{\"cases\":[{\"toPort\":\"one\"}]}}";
        String configNodes = "[" + START + "," + configCases + "," + ACTION + "," + END + "]";
        assertThat(validator.validate(definition(configNodes, edges)).valid()).isTrue();
    }

    @Test
    void rejectsBadParallelPort() {
        String document = parallelGraph("").replace("{\"from\":\"p\",\"to\":\"a\"}",
                "{\"from\":\"p\",\"port\":\"9lives\",\"to\":\"a\"}");
        assertHasError(document, "EDGE_PORT_NOT_ALLOWED");
    }

    @Test
    void rejectsBadJoinPort() {
        String nodes = "[" + START + ",{\"id\":\"a1\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send@v1\"},"
                + "{\"id\":\"j\",\"type\":\"JOIN\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"a1\"},{\"from\":\"a1\",\"port\":\"success\",\"to\":\"j\"},"
                + "{\"from\":\"a1\",\"port\":\"failure\",\"to\":\"j\"},"
                + "{\"from\":\"j\",\"port\":\"maybe\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "EDGE_PORT_NOT_ALLOWED");
    }

    @Test
    void rejectsBadForeachPort() {
        String edges = "[{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"port\":\"loop\",\"to\":\"a\"},"
                + "{\"from\":\"a\",\"to\":\"e\"}]";
        assertHasError(foreachGraph("", edges), "EDGE_PORT_NOT_ALLOWED");
    }

    @Test
    void rejectsBadDelayAndSetVariablePorts() {
        String delayNodes = "[" + START + ",{\"id\":\"d\",\"type\":\"DELAY\"}," + END + "]";
        String delayEdges = "[{\"from\":\"s\",\"to\":\"d\"},{\"from\":\"d\",\"port\":\"failure\",\"to\":\"e\"}]";
        assertHasError(definition(delayNodes, delayEdges), "EDGE_PORT_NOT_ALLOWED");

        String varNodes = "[" + START + ",{\"id\":\"v\",\"type\":\"SET_VARIABLE\","
                + "\"config\":{\"name\":\"vars.x\",\"value\":1}}," + END + "]";
        String varEdges = "[{\"from\":\"s\",\"to\":\"v\"},{\"from\":\"v\",\"port\":\"failure\",\"to\":\"e\"}]";
        assertHasError(definition(varNodes, varEdges), "EDGE_PORT_NOT_ALLOWED");
    }

    @Test
    void rejectsBadSubPlaybookPort() {
        String nodes = "[" + START + ",{\"id\":\"sp\",\"type\":\"SUB_PLAYBOOK\","
                + "\"playbookVersionId\":\"pv-1\"}," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"sp\"},{\"from\":\"sp\",\"port\":\"timeout\",\"to\":\"e\"}]";
        assertHasError(definition(nodes, edges), "EDGE_PORT_NOT_ALLOWED");
    }

    // ------------------------------------------------------------- root limits

    @Test
    void rejectsRootMaxNodeExecutionsOutOfRange() {
        assertHasError(definition("{\"maxNodeExecutions\":501}", SE_NODES, SE_EDGES),
                "NODE_EXECUTION_LIMIT_INVALID");
        assertHasError(definition("{\"maxNodeExecutions\":0}", SE_NODES, SE_EDGES),
                "NODE_EXECUTION_LIMIT_INVALID");
    }

    @Test
    void rejectsRootParallelismProblems() {
        assertHasError(definition("{\"maxParallelism\":\"5\"}", SE_NODES, SE_EDGES),
                "PARALLELISM_LIMIT_INVALID");
        assertHasError(definition("{\"maxParallelism\":0}", SE_NODES, SE_EDGES),
                "PARALLELISM_LIMIT_INVALID");
        assertHasError(definition("{\"maxParallelism\":11}", SE_NODES, SE_EDGES),
                "PARALLELISM_LIMIT_INVALID");
    }

    @Test
    void rejectsInvalidExecutionTimeout() {
        assertHasError(definition("{\"executionTimeout\":\"bogus\"}", SE_NODES, SE_EDGES),
                "EXECUTION_TIMEOUT_INVALID");
        assertHasError(definition("{\"executionTimeout\":\"PT0S\"}", SE_NODES, SE_EDGES),
                "EXECUTION_TIMEOUT_INVALID");
        assertHasError(definition("{\"executionTimeout\":\"\"}", SE_NODES, SE_EDGES),
                "EXECUTION_TIMEOUT_INVALID");
        assertHasError(definition("{\"executionTimeout\":\"P31D\"}", SE_NODES, SE_EDGES),
                "EXECUTION_TIMEOUT_INVALID");
        assertHasError(definition("{\"executionTimeout\":5}", SE_NODES, SE_EDGES),
                "EXECUTION_TIMEOUT_INVALID");
        assertNoError(definition("{\"executionTimeout\":\"PT2H\"}", SE_NODES, SE_EDGES),
                "EXECUTION_TIMEOUT_INVALID");
    }

    // ------------------------------------------------------------ risk warnings

    @Test
    void warnsWhenHighRiskActionLacksApprovalGate() {
        String isolate = ACTION.replace("socp.notify/send@v1", "endpoint/isolate-host@1");
        String document = definition("[" + START + "," + isolate + "," + END + "]", SE_EDGES);
        assertHasWarning(document, "HIGH_RISK_ACTIONS_PRESENT");
        assertHasWarning(document, "HIGH_RISK_APPROVAL_GATE_RECOMMENDED");
    }

    @Test
    void highRiskActionCoveredByGateProducesNoCoverageWarning() {
        String isolate = ACTION.replace("socp.notify/send@v1", "endpoint/isolate-host@1");
        String nodes = "[" + START + ",{\"id\":\"ap\",\"type\":\"APPROVAL\"}," + isolate + "," + END + "]";
        String edges = "[{\"from\":\"s\",\"to\":\"ap\"},{\"from\":\"ap\",\"port\":\"approved\",\"to\":\"a\"},"
                + "{\"from\":\"ap\",\"port\":\"rejected\",\"to\":\"e\"},{\"from\":\"a\",\"to\":\"e\"}]";
        String document = definition(nodes, edges);
        assertHasWarning(document, "HIGH_RISK_ACTIONS_PRESENT");
        assertNoWarning(document, "HIGH_RISK_APPROVAL_GATE_RECOMMENDED");
    }

    // ------------------------------------------------------------ canonical hash

    @Test
    void canonicalHashIsStableAndFallbackSafe() {
        assertThat(validator.canonicalHash("{\"b\":2,\"a\":1}")).hasSize(64);
        assertThat(validator.canonicalHash("{\"a\":1,\"b\":2}")).hasSize(64);
        assertThat(validator.canonicalHash("{broken")).hasSize(64).isNotBlank();
    }

    // ------------------------------------------------- MANUAL_TASK form schema

    @Test
    void rejectsManualFormTypeProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"type\":5}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"type\":\"blob\"}"), "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormTypeArrayProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"type\":[]}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"type\":[\"string\",\"blob\"]}"),
                "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormRequiredProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"required\":\"a\"}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"required\":[\"\"]}"), "MANUAL_FORM_INVALID");
        String tooMany = ",\"formSchema\":{\"required\":"
                + repeatedQuotedValues("field-", 65) + "}";
        assertHasError(manualTaskDef(tooMany), "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormPropertiesProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"properties\":[]}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"properties\":{\"x\":5}}"), "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormItemsProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"items\":5}"), "MANUAL_FORM_INVALID");
        assertNoError(manualTaskDef(",\"formSchema\":{\"items\":{\"type\":\"string\"}}"),
                "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormNumericBoundProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"minimum\":\"low\"}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"minLength\":\"x\"}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"minItems\":-1}"), "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormPatternProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"pattern\":5}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"pattern\":\"([\"}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"pattern\":\"(a+)+\"}"), "MANUAL_FORM_INVALID");
        assertNoError(manualTaskDef(",\"formSchema\":{\"pattern\":\"^[A-Za-z0-9_-]+$\"}"),
                "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormAdditionalPropertiesAndEnumProblems() {
        assertHasError(manualTaskDef(",\"formSchema\":{\"additionalProperties\":5}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"enum\":\"one\"}"), "MANUAL_FORM_INVALID");
        assertHasError(manualTaskDef(",\"formSchema\":{\"enum\":"
                + repeatedQuotedValues("v", 101) + "}"), "MANUAL_FORM_INVALID");
    }

    @Test
    void rejectsManualFormExceedingNestingDepth() {
        assertHasError(manualTaskDef(",\"formSchema\":" + deepFormSchema(22)), "MANUAL_FORM_INVALID");
    }

    @Test
    void acceptsValidManualFormSchema() {
        String form = ",\"formSchema\":{\"type\":\"object\","
                + "\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1}},"
                + "\"required\":[\"name\"]}";
        assertNoError(manualTaskDef(form), "MANUAL_FORM_INVALID");
    }

    // ------------------------------------------------- registry-backed contracts

    private SoarConnectorRegistry registryFor(ActionDescriptor action, String actionRef) {
        SoarConnectorRegistry registry = mock(SoarConnectorRegistry.class);
        given(registry.descriptorForAction(actionRef))
                .willReturn(Optional.of(new ConnectorDescriptor("conn", 1, "Conn", true, List.of(action))));
        given(registry.canonicalActionRef(anyString())).willAnswer(invocation -> invocation.getArgument(0));
        given(registry.actionDescriptor(actionRef)).willReturn(Optional.of(action));
        return registry;
    }

    @Test
    void actionWithRequiredConnectionNeedsConnectionRef() {
        ActionDescriptor action = new ActionDescriptor("needconn", 1, "NeedConn", "NeedConn", "LOW",
                "NONE", "NONE", true, List.of(), Map.of(), Map.of());
        SoarDefinitionValidator registryValidator =
                new SoarDefinitionValidator(new ObjectMapper(), registryFor(action, "conn/needconn@1"));
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/needconn@1\"},"
                + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.errors()).anyMatch(issue -> "ACTION_CONNECTION_REQUIRED".equals(issue.code()));
    }

    @Test
    void rejectsTargetTypeNotSupportedByAction() {
        ActionDescriptor action = new ActionDescriptor("typed", 1, "Typed", "Typed", "LOW",
                "NONE", "NONE", false, List.of("host"), Map.of(), Map.of());
        SoarDefinitionValidator registryValidator =
                new SoarDefinitionValidator(new ObjectMapper(), registryFor(action, "conn/typed@1"));
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/typed@1\","
                + "\"target\":{\"type\":\"printer\"}}," + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.errors()).anyMatch(issue -> "ACTION_TARGET_TYPE_INVALID".equals(issue.code()));
    }

    @Test
    void rejectsNonObjectActionParameters() {
        ActionDescriptor action = new ActionDescriptor("actor", 1, "Actor", "Actor", "LOW",
                "NONE", "NONE", false, List.of(), Map.of(), Map.of());
        SoarDefinitionValidator registryValidator =
                new SoarDefinitionValidator(new ObjectMapper(), registryFor(action, "conn/actor@1"));
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/actor@1\","
                + "\"parameters\":\"boom\"}," + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.errors()).anyMatch(issue -> "ACTION_PARAMETERS_INVALID".equals(issue.code()));
    }

    @Test
    void validatesKnownActionParameterSchemaAndRequiredFields() {
        Map<String, Object> targetSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("id"),
                "properties", Map.of("id", Map.of("type", "string", "maxLength", 8)));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("alertId"),
                "properties", Map.of("alertId", Map.of("type", "string", "maxLength", 16),
                        "target", targetSchema));
        ActionDescriptor action = new ActionDescriptor("typed", 1, "Typed", "Typed", "LOW",
                "NONE", "NONE", false, List.of(), schema, Map.of());
        SoarDefinitionValidator registryValidator =
                new SoarDefinitionValidator(new ObjectMapper(), registryFor(action, "conn/typed@1"));
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/typed@1\","
                + "\"parameters\":{\"alertId\":123,\"extra\":true}}," + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.errors()).extracting(DefinitionIssue::code)
                .contains("ACTION_PARAMETER_TYPE_INVALID", "ACTION_PARAMETER_UNKNOWN");

        String missing = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/typed@1\","
                + "\"parameters\":{}}," + END + "]";
        DefinitionValidationResult missingResult = registryValidator.validate(definition(missing, SE_EDGES));
        assertThat(missingResult.errors()).anyMatch(issue -> "ACTION_PARAMETER_REQUIRED".equals(issue.code()));

        String omitted = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/typed@1\"},"
                + END + "]";
        DefinitionValidationResult omittedResult = registryValidator.validate(definition(omitted, SE_EDGES));
        assertThat(omittedResult.errors()).anyMatch(issue -> "ACTION_PARAMETER_REQUIRED".equals(issue.code()));

        String nested = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/typed@1\","
                + "\"parameters\":{\"alertId\":\"ok\",\"target\":{\"extra\":true}}}," + END + "]";
        DefinitionValidationResult nestedResult = registryValidator.validate(definition(nested, SE_EDGES));
        assertThat(nestedResult.errors()).extracting(DefinitionIssue::code)
                .contains("ACTION_PARAMETER_REQUIRED", "ACTION_PARAMETER_UNKNOWN");
    }

    @Test
    void criticalRiskActionIsForbidden() {
        ActionDescriptor action = new ActionDescriptor("nuke", 1, "Nuke", "Nuke", "CRITICAL",
                "NONE", "NONE", false, List.of(), Map.of(), Map.of());
        SoarDefinitionValidator registryValidator =
                new SoarDefinitionValidator(new ObjectMapper(), registryFor(action, "conn/nuke@1"));
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/nuke@1\"},"
                + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.errors()).anyMatch(issue -> "ACTION_RISK_CRITICAL_FORBIDDEN".equals(issue.code()));
    }

    @Test
    void rejectsRetryingNonIdempotentSideEffects() {
        ActionDescriptor action = new ActionDescriptor("risky", 1, "Risky", "Risky", "LOW",
                "IRREVERSIBLE", "NONE", false, List.of(), Map.of(), Map.of());
        SoarDefinitionValidator registryValidator =
                new SoarDefinitionValidator(new ObjectMapper(), registryFor(action, "conn/risky@1"));
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/risky@1\","
                + "\"retry\":{\"maxAttempts\":2}}," + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.errors()).anyMatch(issue -> "ACTION_RETRY_REQUIRES_IDEMPOTENCY".equals(issue.code()));
    }

    @Test
    void warnsOnHighRiskCompensation() {
        ActionDescriptor main = new ActionDescriptor("main", 1, "Main", "Main", "LOW",
                "NONE", "NONE", false, List.of(), Map.of(), Map.of());
        ActionDescriptor undo = new ActionDescriptor("undo", 1, "Undo", "Undo", "HIGH",
                "NONE", "NONE", false, List.of(), Map.of(), Map.of());
        SoarConnectorRegistry registry = mock(SoarConnectorRegistry.class);
        given(registry.descriptorForAction("conn/main@1"))
                .willReturn(Optional.of(new ConnectorDescriptor("conn", 1, "Conn", true, List.of(main))));
        given(registry.canonicalActionRef(anyString())).willAnswer(invocation -> invocation.getArgument(0));
        given(registry.actionDescriptor("conn/main@1")).willReturn(Optional.of(main));
        given(registry.actionDescriptor("conn/undo@1")).willReturn(Optional.of(undo));

        SoarDefinitionValidator registryValidator = new SoarDefinitionValidator(new ObjectMapper(), registry);
        String nodes = "[" + START + ",{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"conn/main@1\","
                + "\"compensationRef\":\"conn/undo@1\"}," + END + "]";
        DefinitionValidationResult result = registryValidator.validate(definition(nodes, SE_EDGES));
        assertThat(result.warnings()).anyMatch(issue -> "COMPENSATION_HIGH_RISK".equals(issue.code()));
    }

    // ------------------------------------------------------- credential scanning

    @Test
    void rejectsEmbeddedCredentialInParameterArray() {
        String nodes = "[" + START + "," + ACTION.substring(0, ACTION.length() - 1)
                + ",\"parameters\":{\"history\":[\"bearer: sup3rsecret\"]}}," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "ACTION_EMBEDDED_CREDENTIAL_FORBIDDEN");
    }

    @Test
    void rejectsEmbeddedApiTokenInTarget() {
        String token = "ghp_" + "a".repeat(40);
        String nodes = "[" + START + "," + ACTION.substring(0, ACTION.length() - 1)
                + ",\"target\":{\"id\":\"" + token + "\"}}," + END + "]";
        assertHasError(definition(nodes, SE_EDGES), "ACTION_EMBEDDED_CREDENTIAL_FORBIDDEN");
    }
}
