package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SuppressorTest {

    @Test
    void boundsCardinalityWhenKeysAreHighCardinality() {
        try (Suppressor suppressor = new Suppressor(Duration.ofHours(1), 2)) {
            for (int i = 0; i < 20; i++) {
                suppressor.allow(new Alert("rule", "rule", Severity.INFO,
                        "message", "entity-" + i, List.of()));
            }

            assertTrue(suppressor.trackedKeys() <= 2);
        }
    }
}
