package com.socp.rule;

import com.socp.rule.state.RuleStateLimits;
import com.socp.rule.state.RuleStateMap;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleStateMapTest {

    @Test
    void evictsOldestKeyWhenCapacityIsReached() {
        RuleStateMap<String> map = new RuleStateMap<>(new RuleStateLimits(2, Duration.ofMinutes(1)));
        map.get("one", () -> "1");
        map.get("two", () -> "2");
        map.get("three", () -> "3");

        assertEquals(2, map.size());
        assertTrue(map.evictions() >= 1);
    }

    @Test
    void evictsIdleKeys() throws InterruptedException {
        RuleStateMap<String> map = new RuleStateMap<>(new RuleStateLimits(10, Duration.ofMillis(1)));
        map.get("one", () -> "1");
        Thread.sleep(5);

        assertEquals(0, map.size());
        assertEquals(1, map.evictions());
    }
}
