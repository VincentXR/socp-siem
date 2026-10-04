package com.socp.alert.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 告警处置生命周期（对应 t_alarm_disposition.status 与 t_alarm.status）。
 *
 * <p>重开只能经 {@code RESOLVED/CLOSED -> INVESTIGATING}，不允许退回 OPEN，
 * 这样时间线上的"重开"与"首次分诊"才可区分；此前任意跳转都合法，两者无法辨别。
 */
public enum AlarmState {
    OPEN,
    INVESTIGATING,
    RESOLVED,
    CLOSED,
    /** Matched an active suppression window; still counted, never dispatched as work. */
    SUPPRESSED;

    /**
     * Wire vocabulary shared by request DTO validation. Kept equal to the enum
     * constant names by {@code AlarmStateTest}; annotation values cannot be
     * derived from {@link #values()} because they must be compile-time constants.
     */
    public static final String PATTERN = "OPEN|INVESTIGATING|RESOLVED|CLOSED|SUPPRESSED";

    private static final Map<AlarmState, Set<AlarmState>> TRANSITIONS = Map.of(
            OPEN, EnumSet.of(INVESTIGATING, RESOLVED, CLOSED, SUPPRESSED),
            INVESTIGATING, EnumSet.of(OPEN, RESOLVED, CLOSED, SUPPRESSED),
            RESOLVED, EnumSet.of(INVESTIGATING, CLOSED),
            CLOSED, EnumSet.of(INVESTIGATING),
            // Un-suppressing goes back to work, or the analyst accepts the silence
            // and closes the alarm without ever triaging it.
            SUPPRESSED, EnumSet.of(INVESTIGATING, CLOSED));

    private static final Set<AlarmState> ACTIVE_STATES = EnumSet.of(OPEN, INVESTIGATING);

    /**
     * Query-side vocabulary for unfinished triage, behind the read-only
     * {@code status=ACTIVE} filter. It is not a storable state.
     */
    public static List<String> activeNames() {
        return ACTIVE_STATES.stream().map(Enum::name).toList();
    }

    /** Legal targets from this state. Staying in the same state is not a transition. */
    public boolean canMoveTo(AlarmState target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public static Optional<AlarmState> from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (AlarmState state : values()) {
            if (state.name().equals(normalized)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
