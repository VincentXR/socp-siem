package com.socp.soar.web.definition;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch coverage for the expression evaluator: operators, paths, lists, guards. */
class SoarExpressionEngineBranchCoverageTest {

    @Test
    void orCompositionEvaluatesSecondOperand() {
        assertThat(SoarExpressionEngine.evaluate("false || true", Map.of())).isTrue();
        assertThat(SoarExpressionEngine.evaluate("true || false", Map.of())).isTrue();
        assertThat(SoarExpressionEngine.evaluate("false || false", Map.of())).isFalse();
    }

    @Test
    void comparisonOperatorsCoverEachSwitchCase() {
        assertThat(SoarExpressionEngine.evaluate("score == 90", Map.of("score", 90))).isTrue();
        assertThat(SoarExpressionEngine.evaluate("score != 90", Map.of("score", 91))).isTrue();
        assertThat(SoarExpressionEngine.evaluate("score > 90", Map.of("score", 95))).isTrue();
        assertThat(SoarExpressionEngine.evaluate("score > 90", Map.of("score", 85))).isFalse();
        assertThat(SoarExpressionEngine.evaluate("score <= 90", Map.of("score", 90))).isTrue();
        assertThat(SoarExpressionEngine.evaluate("score <= 90", Map.of("score", 95))).isFalse();
        assertThat(SoarExpressionEngine.evaluate("name == 'web-1'", Map.of("name", "web-1"))).isTrue();
    }

    @Test
    void bareOperandFallsBackToBooleanParse() {
        assertThat(SoarExpressionEngine.evaluate("flag", Map.of("flag", true))).isTrue();
        assertThat(SoarExpressionEngine.evaluate("flag", Map.of("flag", false))).isFalse();
    }

    @Test
    void listLiteralsAreResolvedAndComparedStructurally() {
        assertThat(SoarExpressionEngine.evaluate("[1, 2] == [1, 2]", Map.of())).isTrue();
        assertThat(SoarExpressionEngine.evaluate("[1, 2] == [2, 1]", Map.of())).isFalse();
    }

    @Test
    void dottedPathsTraverseNestedContextsAndMissingLeavesAreNull() {
        assertThat(SoarExpressionEngine.evaluate("data.host == 'web-1'",
                Map.of("data", Map.of("host", "web-1")))).isTrue();
        // No flattened prefix and a missing top-level key: traversal yields
        // null, which never compares equal to a string literal.
        assertThat(SoarExpressionEngine.evaluate("missing == 'x'", Map.of())).isFalse();
    }

    @Test
    void parenthesizedWholeExpressionSkipsQuotesWhileCheckingDepth() {
        // A fully enclosed expression is unwrapped and re-evaluated.
        assertThat(SoarExpressionEngine.evaluate("(score >= 90)", Map.of("score", 95))).isTrue();
        assertThat(SoarExpressionEngine.evaluate("(score >= 90)", Map.of("score", 85))).isFalse();
        // The parenthesis-depth scan must ignore parentheses inside quotes.
        assertThat(SoarExpressionEngine.evaluate("(name == ')')", Map.of("name", ")"))).isTrue();
        assertThat(SoarExpressionEngine.evaluate(
                "(verdict == 'malicious') && (score >= 90)",
                Map.of("verdict", "malicious", "score", 95))).isTrue();
    }
}
