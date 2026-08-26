package com.socp.platform.client.http;

import com.socp.platform.client.config.SocpClientProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalEndpointPolicyTest {

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
}
