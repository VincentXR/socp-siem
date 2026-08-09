package com.socp.rule.model;

/**
 * 告警/事件严重级别。数值越大越严重，便于比较。由 com.siem 迁移。
 */
public enum Severity {
    INFO(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int level;

    Severity(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /** 是否达到（>=）给定的最低严重级别 */
    public boolean atLeast(Severity other) {
        return this.level >= other.level;
    }

    public static Severity fromLevel(int level) {
        for (Severity s : values()) {
            if (s.level == level) return s;
        }
        return level >= CRITICAL.level ? CRITICAL : INFO;
    }
}
