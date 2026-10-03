package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyRequestsTest {
    @Test void postAllowlistIsExactAndDoesNotGrantNeighborWrites() {
        assertThat(ReadOnlyRequests.allowed("POST", "/detect-web/api/v1/rules/lookup")).isTrue();
        assertThat(ReadOnlyRequests.allowed("POST", "/alert-web/api/alarms/technique-counts")).isTrue();
        for (String path : new String[] { "/detect-web/api/v1/rules", "/detect-web/api/v1/rules/lookup/activate",
                "/detect-web/api/v1/rules/lookup/../activate", "/detect-web/api/v1/rules/lookup/",
                "/soar-web/api/runs", "/alert-web/api/alarms" }) {
            assertThat(ReadOnlyRequests.allowed("POST", path)).as(path).isFalse();
        }
        assertThat(ReadOnlyRequests.allowed("DELETE", "/detect-web/api/v1/rules/lookup")).isFalse();
    }
}
