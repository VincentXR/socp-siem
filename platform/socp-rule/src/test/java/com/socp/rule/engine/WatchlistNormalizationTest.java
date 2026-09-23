package com.socp.rule.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchlistNormalizationTest {
    @Test
    @ResourceLock(Resources.LOCALE)
    void identitiesRemainStableWhenWriterAndReaderUseDifferentLocales() {
        Locale original = Locale.getDefault();
        String tenant = "watchlist-locale-test";
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Watchlists.put(tenant, " IDENTITIES ", List.of(" ADMIN "));
            Locale.setDefault(Locale.US);
            assertTrue(Watchlists.contains(tenant, "identities", "admin"));
            assertTrue(Watchlists.contains(tenant, "IDENTITIES", "ADMIN"));
        } finally {
            Locale.setDefault(original);
            Watchlists.delete(tenant, "identities");
        }
    }
}
