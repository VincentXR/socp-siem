package com.socp.soar.web.model;

/** Wire-compatible action result states used by both local and Temporal execution. */
public enum PlaybookActionStatus {
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
        return SUCCESS.wireValue.equals(value) || SIMULATED.wireValue.equals(value);
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
