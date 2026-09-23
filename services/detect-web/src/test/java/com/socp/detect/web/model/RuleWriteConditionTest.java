package com.socp.detect.web.model;

import com.socp.platform.error.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleWriteConditionTest {
    private final Map<String, Object> rule = Map.of("revisionToken", "a".repeat(64));

    @Test void requiresSpecificValidatorsAndUsesStrongComparison() {
        for (String missing : new String[]{null, "", " ", "*"}) {
            assertEquals(428, assertThrows(ApiException.class, () -> RuleWriteCondition.parse(missing, null, false)).getCode());
        }
        String tag = RuleWriteCondition.etag(rule);
        assertDoesNotThrow(() -> RuleWriteCondition.parse(tag, null, false).check(rule));
        assertDoesNotThrow(() -> RuleWriteCondition.parse("\"other,tag\", " + tag, null, false).check(rule));
        for (String wrong : List.of("W/" + tag, "\"other\"", "\"\"")) {
            assertEquals(412, assertThrows(ApiException.class, () -> RuleWriteCondition.parse(wrong, null, false).check(rule)).getCode());
        }
        assertEquals(412, assertThrows(ApiException.class, () -> RuleWriteCondition.parse(tag, null, false).check(null)).getCode());
    }

    @Test void boundsAndValidatesTheWholeHeaderBeforeAnyWrite() {
        for (String invalid : List.of("unquoted", "\"tag\" garbage", "\"a\" \"b\"", "*,\"a\"", "\"a\",",
                "\"a\n\"", "\"" + "x".repeat(2048) + "\"", String.join(",", java.util.Collections.nCopies(9, "\"a\"")))) {
            assertEquals(400, assertThrows(ApiException.class, () -> RuleWriteCondition.parse(invalid, null, false)).getCode(), invalid);
        }
    }

    @Test void absenceIsAnExplicitRestoreOnlyPrecondition() {
        var absent = RuleWriteCondition.parse(null, "*", true);
        assertDoesNotThrow(() -> absent.check(null));
        assertEquals(412, assertThrows(ApiException.class, () -> absent.check(rule)).getCode());
        assertEquals(400, assertThrows(ApiException.class, () -> RuleWriteCondition.parse(null, "*", false)).getCode());
        assertEquals(400, assertThrows(ApiException.class, () -> RuleWriteCondition.parse(RuleWriteCondition.etag(rule), "*", true)).getCode());
    }
}
