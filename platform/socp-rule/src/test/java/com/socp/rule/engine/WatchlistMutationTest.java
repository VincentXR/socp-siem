package com.socp.rule.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchlistMutationTest {
    @BeforeEach void prepare() { Watchlists.clear(); }
    @AfterEach void clear() { Watchlists.clear(); }

    @Test void appendChecksTheResultingSizeWithoutModifyingThePreviousStateOnFailure() {
        var initial = IntStream.range(0, WatchlistLimits.MAX_VALUES).mapToObj(i -> "value-" + i).toList();
        Watchlists.put("tenant-a", "bounded", initial);
        assertThrows(IllegalArgumentException.class, () -> Watchlists.add("tenant-a", "bounded", List.of("overflow")));
        assertEquals(WatchlistLimits.MAX_VALUES, Watchlists.size("tenant-a", "bounded"));
        Watchlists.add("tenant-a", "bounded", List.of("VALUE-0"));
        assertEquals(WatchlistLimits.MAX_VALUES, Watchlists.size("tenant-a", "bounded"));
    }

    @Test void namesAndMembersAreValidatedBeforeChangingState() {
        assertThrows(IllegalArgumentException.class, () -> Watchlists.put("tenant-a", " ", List.of("value")));
        assertThrows(IllegalArgumentException.class, () -> Watchlists.put("tenant-a", "a".repeat(256), List.of("value")));
        assertThrows(IllegalArgumentException.class, () -> Watchlists.put("tenant-a", "safe", List.of("a".repeat(257))));
        assertTrue(Watchlists.names("tenant-a").isEmpty());
    }

    @Test void inheritedValuesAreMergedButDeletedTemplatesAreNotReintroducedByAppend() {
        Watchlists.putTemplate("admins", List.of("root"));
        assertEquals(Set.of("root", "alice"), Watchlists.add("tenant-a", "admins", List.of("alice")));
        Watchlists.delete("tenant-a", "admins");
        assertEquals(Set.of("bob"), Watchlists.add("tenant-a", "admins", List.of("bob")));
        assertEquals(Set.of("root"), Watchlists.values("tenant-b", "admins"));
    }

    @Test void returnedValuesCannotMutateTheRuleRegistry() {
        Watchlists.put("tenant-a", "admins", List.of("root"));
        assertThrows(UnsupportedOperationException.class, () -> Watchlists.values("tenant-a", "admins").add("intruder"));
        assertEquals(Set.of("root"), Watchlists.values("tenant-a", "admins"));
    }

    @Test void createRejectsExistingOwnedOrInheritedListsButAllowsRecreationAfterDeletion() {
        Watchlists.putTemplate("admins", List.of("root"));
        assertThrows(Watchlists.AlreadyExistsException.class,
                () -> Watchlists.create("tenant-a", " ADMINS ", List.of("alice")));
        Watchlists.create("tenant-a", " NEW ", List.of("Alice"));
        assertThrows(Watchlists.AlreadyExistsException.class,
                () -> Watchlists.create("tenant-a", "new", List.of("bob")));
        assertEquals(Set.of("alice"), Watchlists.values("tenant-a", "new"));
        Watchlists.create("tenant-b", "new", List.of("bob"));
        assertEquals(Set.of("bob"), Watchlists.values("tenant-b", "new"));
        Watchlists.delete("tenant-a", "admins");
        assertEquals(Set.of("alice"), Watchlists.create("tenant-a", "admins", List.of("alice")));
        Watchlists.delete("tenant-a", "new");
        assertEquals(Set.of("carol"), Watchlists.create("tenant-a", "new", List.of("carol")));
    }
}
