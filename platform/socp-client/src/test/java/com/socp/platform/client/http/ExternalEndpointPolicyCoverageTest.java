package com.socp.platform.client.http;

import com.socp.platform.client.config.SocpClientProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SSRF trust-boundary coverage for the shared egress policy. */
class ExternalEndpointPolicyCoverageTest {

    private ExternalEndpointPolicy policy(SocpClientProperties properties) {
        return new ExternalEndpointPolicy(properties);
    }

    @Test
    void rejectsBlankAndNullEndpoints() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate(null)).contains("empty");
        assertThat(policy(properties).validate("   ")).contains("empty");
    }

    @Test
    void rejectsUnparsableUris() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("https://exa mple.com/hook"))
                .startsWith("invalid external endpoint");
    }

    @Test
    void rejectsNonHttpSchemes() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("ftp://example.com/file"))
                .contains("must use HTTP or HTTPS");
        assertThat(policy(properties).validate("file:///etc/passwd"))
                .contains("must use HTTP or HTTPS");
    }

    @Test
    void rejectsPlainHttpWhenHttpsOnlyIsEnabled() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("http://example.com/hook", List.of("example.com"),
                true, false)).contains("must use HTTPS");
    }

    @Test
    void rejectsFragmentsAndCredentialQueries() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("https://example.com/hook#fragment"))
                .contains("userinfo or a fragment");
        assertThat(policy(properties).validate("https://example.com/hook?token=abc"))
                .contains("credentials in the query");
        assertThat(policy(properties).validate("https://example.com/hook?API-KEY=abc"))
                .contains("credentials in the query");
        assertThat(policy(properties).validate("https://example.com/hook?ok=1"))
                .doesNotContain("credentials");
    }

    @Test
    void rejectsEndpointsWithoutAUsableHost() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("https:///only-a-path"))
                .contains("host is empty or invalid");
    }

    @Test
    void rejectsHostsOutsideTheAllowlistBeforeAnyResolution() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("https://example.com/hook", List.of("other.com"),
                false, true)).contains("not allowlisted");
        assertThat(policy(properties).validate("https://badexample.com/hook",
                List.of("*.example.com"), false, true)).contains("not allowlisted");
    }

    @Test
    void rejectsLoopbackResolutionUnlessPrivateNetworksAreAllowed() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("http://localhost:8080/hook", List.of("localhost"),
                false, false)).contains("private or reserved");
        assertThat(policy(properties).validate("http://localhost:8080/hook", List.of("localhost"),
                false, true)).isNull();
    }

    @Test
    void rejectsIpv6LoopbackResolution() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("http://[::1]:8080/hook", List.of("[::1]"),
                false, false)).contains("private or reserved");
    }

    @Test
    void coversResolutionFailureAndEmbeddedIpv4TunnelRanges() throws Exception {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(policy(properties).validate("https://missing.socp.invalid/hook",
                List.of("missing.socp.invalid"), false, true)).contains("cannot be resolved");

        Method blocked = ExternalEndpointPolicy.class.getDeclaredMethod("isBlocked", InetAddress.class);
        blocked.setAccessible(true);

        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 203;
        mapped[13] = 0;
        mapped[14] = 113;
        mapped[15] = 7;
        assertThat(isBlocked(blocked, mapped)).isTrue();

        byte[] sixToFour = new byte[16];
        sixToFour[0] = 0x20;
        sixToFour[1] = 0x02;
        sixToFour[2] = (byte) 198;
        sixToFour[3] = 18;
        sixToFour[4] = 0;
        sixToFour[5] = 7;
        assertThat(isBlocked(blocked, sixToFour)).isTrue();

        byte[] teredo = new byte[16];
        teredo[0] = 0x20;
        teredo[1] = 0x01;
        assertThat(isBlocked(blocked, teredo)).isTrue();

        byte[] nat64 = new byte[16];
        nat64[1] = 0x64;
        nat64[2] = (byte) 0xff;
        nat64[3] = (byte) 0x9b;
        assertThat(isBlocked(blocked, nat64)).isTrue();
    }

    private static boolean isBlocked(Method method, byte[] address) throws Exception {
        return (boolean) method.invoke(null, InetAddress.getByAddress(address));
    }
}
