package com.socp.platform.client.http;

import com.socp.platform.client.config.SocpClientProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalEndpointPolicyTest {

    @AfterEach
    void ensureNoPinLeaksBetweenTests() {
        PinnedDnsResolverProvider.unpin("localhost");
    }

    @Test
    void requiresAnExplicitHostAllowlist() {
        SocpClientProperties properties = new SocpClientProperties();
        assertThat(new ExternalEndpointPolicy(properties).validate("https://example.com/hook"))
                .contains("not allowlisted");
    }

    @Test
    void rejectsPrivateResolutionUnlessExplicitlyEnabled() {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);

        assertThat(new ExternalEndpointPolicy(properties).validate("http://localhost:8080/hook"))
                .contains("private or reserved");

        properties.setExternalAllowPrivateNetworks(true);
        assertThat(new ExternalEndpointPolicy(properties).validate("http://localhost:8080/hook"))
                .isNull();
    }

    @Test
    void rejectsCredentialsEmbeddedInTheUrl() {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of("example.com"));
        assertThat(new ExternalEndpointPolicy(properties).validate("https://user:pass@example.com/hook"))
                .contains("userinfo");
    }

    @Test
    void appliesTheSameRulesToASeparateConnectorConfiguration() {
        SocpClientProperties properties = new SocpClientProperties();
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties);

        assertThat(policy.validate("http://localhost:8080/hook", List.of("localhost"),
                false, false)).contains("private or reserved");
    }

    @Test
    void validatePinnedPinsTheResolutionUntilTheEndpointIsClosed() throws Exception {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(true);
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties);

        try (PinnedEndpoint pinned = policy.validatePinned("http://localhost:8080/hook")) {
            assertThat(pinned.isRejected()).isFalse();
            assertThat(pinned.rejectionReason()).isNull();
            assertThat(pinned.host()).isEqualTo("localhost");
            assertThat(pinned.addresses()).isNotEmpty();
            assertThat(PinnedDnsResolverProvider.isPinned("localhost")).isTrue();

            // 钉住期间，同线程对该主机的解析只会返回已校验地址
            byte[] resolved = InetAddress.getByName("localhost").getAddress();
            assertThat(pinned.addresses())
                    .anyMatch(address -> Arrays.equals(address.getAddress(), resolved));
        }
        assertThat(PinnedDnsResolverProvider.isPinned("localhost")).isFalse();
    }

    @Test
    void validatePinnedDoesNotPinRejectedEndpoints() {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(false);
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties);

        try (PinnedEndpoint pinned = policy.validatePinned("http://localhost:8080/hook")) {
            assertThat(pinned.isRejected()).isTrue();
            assertThat(pinned.rejectionReason()).contains("private or reserved");
            assertThat(pinned.host()).isNull();
            assertThat(pinned.addresses()).isEmpty();
            assertThat(PinnedDnsResolverProvider.isPinned("localhost")).isFalse();
        }
        assertThat(PinnedDnsResolverProvider.isPinned("localhost")).isFalse();
    }
}
