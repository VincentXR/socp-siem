package com.socp.rule.engine;

import java.util.Collection;

/** Hard bounds shared by API mutations and rule-side state implementations. */
public final class WatchlistLimits {
    public static final int MAX_LISTS = 500;
    public static final int MAX_VALUES = 10_000;
    public static final int MAX_VALUE_LENGTH = 256;
    public static final int MAX_NAME_LENGTH = 255;

    private WatchlistLimits() { }

    public static void name(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("watchlist name must contain 1 to " + MAX_NAME_LENGTH + " characters");
        }
    }

    public static void values(Collection<String> values) {
        if (values == null || values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("watchlist must contain at most " + MAX_VALUES + " values");
        }
        for (String value : values) {
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("watchlist values must contain at most " + MAX_VALUE_LENGTH + " characters");
            }
        }
    }
}
