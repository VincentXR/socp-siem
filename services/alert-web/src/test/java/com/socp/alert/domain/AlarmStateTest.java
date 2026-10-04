package com.socp.alert.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle graph of {@link AlarmState}: it is the only place that decides which
 * transitions analysts, batch triage and SOAR connectors may perform.
 */
class AlarmStateTest {

    @Test
    void patternStaysInSyncWithConstantNames() {
        String derived = String.join("|", Arrays.stream(AlarmState.values()).map(Enum::name).toList());
        assertThat(AlarmState.PATTERN).isEqualTo(derived);
    }

    @Test
    void everyStateHasAtLeastOneLegalMove() {
        for (AlarmState state : AlarmState.values()) {
            List<AlarmState> legal = Arrays.stream(AlarmState.values()).filter(state::canMoveTo).toList();
            assertThat(legal).as("outgoing transitions from " + state).isNotEmpty();
        }
    }

    @Test
    void allowsDirectTriageAndClosurePaths() {
        assertThat(AlarmState.OPEN.canMoveTo(AlarmState.INVESTIGATING)).isTrue();
        assertThat(AlarmState.OPEN.canMoveTo(AlarmState.RESOLVED)).isTrue();
        assertThat(AlarmState.OPEN.canMoveTo(AlarmState.CLOSED)).isTrue();
        assertThat(AlarmState.INVESTIGATING.canMoveTo(AlarmState.OPEN)).isTrue();
        assertThat(AlarmState.RESOLVED.canMoveTo(AlarmState.CLOSED)).isTrue();
    }

    @Test
    void reopeningReturnsToInvestigatingButNeverStraightToOpen() {
        // Keeping reopen on INVESTIGATING is what makes a reopened alarm observable
        // in the timeline; CLOSED -> OPEN would look like a never-triaged alarm.
        assertThat(AlarmState.CLOSED.canMoveTo(AlarmState.INVESTIGATING)).isTrue();
        assertThat(AlarmState.RESOLVED.canMoveTo(AlarmState.INVESTIGATING)).isTrue();
        assertThat(AlarmState.CLOSED.canMoveTo(AlarmState.OPEN)).isFalse();
        assertThat(AlarmState.CLOSED.canMoveTo(AlarmState.RESOLVED)).isFalse();
        assertThat(AlarmState.RESOLVED.canMoveTo(AlarmState.OPEN)).isFalse();
    }

    @Test
    void suppressionIsReachableFromUntriagedWorkAndLeavesOnlyTriageOrClose() {
        assertThat(AlarmState.OPEN.canMoveTo(AlarmState.SUPPRESSED)).isTrue();
        assertThat(AlarmState.INVESTIGATING.canMoveTo(AlarmState.SUPPRESSED)).isTrue();
        assertThat(AlarmState.SUPPRESSED.canMoveTo(AlarmState.INVESTIGATING)).isTrue();
        assertThat(AlarmState.SUPPRESSED.canMoveTo(AlarmState.CLOSED)).isTrue();
        assertThat(AlarmState.SUPPRESSED.canMoveTo(AlarmState.OPEN)).isFalse();
        assertThat(AlarmState.SUPPRESSED.canMoveTo(AlarmState.RESOLVED)).isFalse();
        // A closed alarm is already terminal; silencing its scope goes through a new
        // suppression window, not by resurrecting the row as SUPPRESSED.
        assertThat(AlarmState.CLOSED.canMoveTo(AlarmState.SUPPRESSED)).isFalse();
        assertThat(AlarmState.RESOLVED.canMoveTo(AlarmState.SUPPRESSED)).isFalse();
    }

    @Test
    void activeVocabularyCoversUnfinishedTriageOnly() {
        // The read-only status=ACTIVE filter is derived from this list, so it must
        // not silently gain RESOLVED/CLOSED, and ACTIVE stays non-storable.
        assertThat(AlarmState.activeNames()).containsExactly("OPEN", "INVESTIGATING");
        assertThat(AlarmState.from("ACTIVE")).isEmpty();
    }

    @Test
    void parsingIsTolerantOfCaseAndPaddingButRejectsUnknownVocabulary() {
        assertThat(AlarmState.from("  investigating ")).contains(AlarmState.INVESTIGATING);
        assertThat(AlarmState.from("CLOSED")).contains(AlarmState.CLOSED);
        assertThat(AlarmState.from("ACTIVE")).isEmpty();
        assertThat(AlarmState.from(null)).isEmpty();
        assertThat(AlarmState.from(" ")).isEmpty();
    }
}
