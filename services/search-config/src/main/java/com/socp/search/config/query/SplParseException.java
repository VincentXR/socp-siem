package com.socp.search.config.query;

/** A client-visible SPL syntax error with a zero-based character position. */
public final class SplParseException extends IllegalArgumentException {
    private final int position;

    public SplParseException(String message, int position) {
        super(message + " at position " + Math.max(0, position));
        this.position = Math.max(0, position);
    }

    public SplParseException(String message, int position, Throwable cause) {
        super(message + " at position " + Math.max(0, position), cause);
        this.position = Math.max(0, position);
    }

    public int position() {
        return position;
    }
}
