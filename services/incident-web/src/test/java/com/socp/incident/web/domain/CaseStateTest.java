package com.socp.incident.web.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lifecycle graph of {@link CaseState}. */
class CaseStateTest {

    @Test
    void patternStaysInSyncWithConstantNames() {
        String derived = String.join("|", Arrays.stream(CaseState.values()).map(Enum::name).toList());
        assertThat(CaseState.PATTERN).isEqualTo(derived);
    }

    @Test
    void everyStateHasAtLeastOneLegalMove() {
        for (CaseState state : CaseState.values()) {
            List<CaseState> legal = Arrays.stream(CaseState.values()).filter(state::canMoveTo).toList();
            assertThat(legal).as("outgoing transitions from " + state).isNotEmpty();
        }
    }

    @Test
    void containmentIsNotClosure() {
        assertThat(CaseState.INVESTIGATING.canMoveTo(CaseState.CONTAINED)).isTrue();
        assertThat(CaseState.CONTAINED.canMoveTo(CaseState.RESOLVED)).isTrue();
        assertThat(CaseState.CONTAINED.canMoveTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.CONTAINED.canMoveTo(CaseState.INVESTIGATING)).isTrue();
    }

    @Test
    void terminalStatesOnlyReopenIntoInvestigation() {
        assertThat(CaseState.CLOSED.canMoveTo(CaseState.INVESTIGATING)).isTrue();
        assertThat(CaseState.CLOSED.canMoveTo(CaseState.OPEN)).isFalse();
        assertThat(CaseState.CLOSED.canMoveTo(CaseState.CONTAINED)).isFalse();
        assertThat(CaseState.RESOLVED.canMoveTo(CaseState.CLOSED)).isTrue();
        assertThat(CaseState.RESOLVED.canMoveTo(CaseState.CONTAINED)).isFalse();
    }

    @Test
    void openVocabularyCoversUnfinishedWorkIncludingContained() {
        assertThat(CaseState.openNames()).containsExactlyInAnyOrder("OPEN", "INVESTIGATING", "CONTAINED");
    }

    @Test
    void parsingRejectsAlarmOnlyVocabulary() {
        // Alarms have no CONTAINED and cases have no bare terminal reopen path;
        // cross-service callers must not be able to smuggle one into the other.
        assertThat(CaseState.from("ACTIVE")).isEmpty();
        assertThat(CaseState.from("contained")).contains(CaseState.CONTAINED);
        assertThat(CaseState.from(null)).isEmpty();
    }
}
