package com.socp.platform.client.http;

import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http2.config.H2Config;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCoreDependencyContractTest {
    @Test
    void resolvedHttpCoreArtifactsStayAlignedOnAPatchedStableRelease() {
        // Inspect the loaded jars, not POM text: Boot's imported BOM can otherwise
        // retain vulnerable versions even when a same-named property is updated.
        String coreVersion = HttpVersion.class.getPackage().getImplementationVersion();
        String h2Version = H2Config.class.getPackage().getImplementationVersion();
        assertThat(coreVersion).as("resolved httpcore5 version").isNotNull();
        assertThat(h2Version).as("resolved httpcore5-h2 version").isEqualTo(coreVersion);
        // CVE-2026-54399 / CVE-2026-54428: stable releases before 5.4.3 and
        // 5.5 prereleases before beta2 are affected. Keep prereleases out entirely.
        assertThat(coreVersion).matches("5\\.\\d+\\.\\d+");
        int[] version = Arrays.stream(coreVersion.split("\\.")).mapToInt(Integer::parseInt).toArray();
        assertThat(version[1]).as("patched HttpCore minor line").isGreaterThanOrEqualTo(4);
        if (version[1] == 4) {
            assertThat(version[2]).as("first patched HttpCore 5.4 release").isGreaterThanOrEqualTo(3);
        }
    }
}
