package com.socp.incident.web.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 案件生命周期（对应 t_incident_case.status）。
 *
 * <p>比告警多一个 {@code CONTAINED} 档：遏制完成不等于结案，仍需 RESOLVED/CLOSED
 * 携带分类与证据。重开只能回到 INVESTIGATING。
 */
public enum CaseState {
    OPEN,
    INVESTIGATING,
    CONTAINED,
    RESOLVED,
    CLOSED;

    /** Wire vocabulary; kept equal to the constant names by {@code CaseStateTest}. */
    public static final String PATTERN = "OPEN|INVESTIGATING|CONTAINED|RESOLVED|CLOSED";

    private static final Map<CaseState, Set<CaseState>> TRANSITIONS = Map.of(
            OPEN, EnumSet.of(INVESTIGATING, CONTAINED, RESOLVED, CLOSED),
            INVESTIGATING, EnumSet.of(OPEN, CONTAINED, RESOLVED, CLOSED),
            CONTAINED, EnumSet.of(INVESTIGATING, RESOLVED, CLOSED),
            RESOLVED, EnumSet.of(INVESTIGATING, CLOSED),
            CLOSED, EnumSet.of(INVESTIGATING));

    private static final Set<CaseState> OPEN_STATES = EnumSet.of(OPEN, INVESTIGATING, CONTAINED);

    public boolean canMoveTo(CaseState next) {
        return TRANSITIONS.get(this).contains(next);
    }

    /** States that still need analyst work; used by open counts and queues. */
    public static List<String> openNames() {
        return OPEN_STATES.stream().map(Enum::name).toList();
    }

    public static Optional<CaseState> from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (CaseState state : values()) {
            if (state.name().equals(normalized)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
