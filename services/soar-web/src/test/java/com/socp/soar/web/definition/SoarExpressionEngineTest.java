package com.socp.soar.web.definition;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoarExpressionEngineTest {

    @Test
    void evaluatesMembershipAndBooleanCompositionWithoutScripting() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("verdict", "malicious");
        context.put("score", 91);
        context.put("nodes.lookup.output", Map.of("verdict", "malicious"));
        assertTrue(SoarExpressionEngine.evaluate(
                "verdict in ['malicious', 'suspicious'] && score >= 90", context));
        assertFalse(SoarExpressionEngine.evaluate("verdict in ['benign']", context));
        assertTrue(SoarExpressionEngine.evaluate(
                "nodes.lookup.output.verdict in ['malicious']", context));
    }

    @Test
    void rejectsScriptAndSupportsSafeNegation() {
        assertFalse(SoarExpressionEngine.isSafe("java.lang.Runtime.exec()"));
        assertTrue(SoarExpressionEngine.evaluate("!(score < 90)", Map.of("score", 91)));
    }
}
