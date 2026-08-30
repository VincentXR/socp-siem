package com.socp.search.config.query;

/** A client-visible SPL semantic error associated with a field or pipeline. */
public final class SplSemanticException extends SplParseException {
    public SplSemanticException(String message, int position) {
        super(message, position);
    }
}
