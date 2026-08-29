package com.socp.soar.web;

import com.socp.soar.web.config.SoarActionConnectorProperties;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.config.TemporalProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Playbook lifecycle and verified response-action execution service. */
@SpringBootApplication(scanBasePackages = "com.socp.soar.web")
@EnableConfigurationProperties({SoarActionConnectorProperties.class, SoarRuntimeProperties.class,
        TemporalProperties.class})
@Import(SocpPlatformAutoConfiguration.class)
public class SoarWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(SoarWebApplication.class, args);
    }
}
