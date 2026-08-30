package com.socp.search.config.query;

/** Typed capabilities for one user-visible SPL field. */
public record FieldDescriptor(
        String name,
        String searchPath,
        String exactPath,
        Type type,
        boolean caseInsensitive,
        boolean rangeAllowed,
        boolean sortable,
        boolean aggregatable,
        boolean containsAllowed,
        boolean registered
) {
    public enum Type {
        KEYWORD, TEXT, DATE, IP, INTEGER, SEVERITY, DYNAMIC_KEYWORD
    }
}
