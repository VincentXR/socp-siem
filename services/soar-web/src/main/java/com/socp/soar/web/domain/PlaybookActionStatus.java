package com.socp.soar.web.domain;
/** Wire-compatible action result states used by both local and Temporal execution. */
public enum PlaybookActionStatus {
    /** Canonical terminal state for a connector action that really ran and was verified. */
    EXECUTED("executed"),
    /** Legacy read-compatibility value; new results must use EXECUTED. */
    SUCCESS("success"),
    FAILED("failed"),
    SKIPPED("skipped"),
    SIMULATED("simulated");

    private final String wireValue;

    PlaybookActionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static boolean isSuccessful(String value) {
        return EXECUTED.wireValue.equals(value)
                || SUCCESS.wireValue.equals(value)
                || SIMULATED.wireValue.equals(value);
    }

    public static boolean isFailed(String value) {
        return FAILED.wireValue.equals(value);
    }

    public static PlaybookActionStatus fromWire(String value) {
        for (PlaybookActionStatus status : values()) {
            if (status.wireValue.equalsIgnoreCase(value)) return status;
        }
        throw new IllegalArgumentException("unknown playbook action status: " + value);
    }
}
