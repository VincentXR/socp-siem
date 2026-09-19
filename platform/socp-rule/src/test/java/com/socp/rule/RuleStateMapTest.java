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

    @Test
    void capacityEnforcementFreesABatchInsteadOfOneKeyPerScan() {
        RuleStateMap<String> map = new RuleStateMap<>(new RuleStateLimits(20, Duration.ofMinutes(5)));
        for (int index = 0; index < 200; index++) {
            map.get("key-" + index, () -> "value");
        }

        assertTrue(map.size() <= 21, "容量必须保持有界，实际=" + map.size());
        // One bounded pass frees a tenth of the bound, so the scan count grows
        // with insertions/2. A regression back to "one full scan per removed
        // key" would report close to one pass per insertion here.
        assertTrue(map.evictionPasses() <= 105,
                "淘汰必须按批摊销，实际 passes=" + map.evictionPasses());
        assertTrue(map.evictions() >= map.evictionPasses(), "每批淘汰至少移除一个键");
        assertTrue(map.stats().containsKey("stateEvictionPasses"), "摊销代价必须可观测");
    }
}
