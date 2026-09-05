package com.socp.soar.web.domain.v2;

import java.util.Locale;

public enum SoarNodeType {
    START, END, ACTION, CONDITION, SWITCH, PARALLEL, JOIN, FOREACH, DELAY,
    APPROVAL, MANUAL_TASK, SUB_PLAYBOOK, SET_VARIABLE;

    public static SoarNodeType parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
