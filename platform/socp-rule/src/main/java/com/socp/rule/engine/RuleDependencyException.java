package com.socp.rule.engine;

/**
 * A rule could not read required external state. The event must remain pending
 * until the dependency recovers or is repaired; retry count must never turn
 * this failure into a skipped rule and a successful durable evaluation.
 */
public final class RuleDependencyException extends RuntimeException {

    public RuleDependencyException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Recognize adapters that wrap dependency failures without trusting their outer type. */
    public static boolean causedBy(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (failure != null && seen.add(failure)) {
            if (failure instanceof RuleDependencyException) return true;
            failure = failure.getCause();
        }
        return false;
    }
}
