package com.socp.platform.client.config;

import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.DetectClient;
import com.socp.platform.client.service.ThreatClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class SocpClientAutoConfigurationTest {

    @Test
    void registersSharedHttpAndTypedClientsForNarrowApplicationScans() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(SocpClientAutoConfiguration.class)) {
            assertThat(context.getBeansOfType(SocpHttpClient.class)).hasSize(1);
            assertThat(context.getBeansOfType(AlertClient.class)).hasSize(1);
            assertThat(context.getBeansOfType(DetectClient.class)).hasSize(1);
            assertThat(context.getBeansOfType(ThreatClient.class)).hasSize(1);
        }
    }
}
