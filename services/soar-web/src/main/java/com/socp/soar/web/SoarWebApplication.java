package com.socp.soar.web;

import com.socp.soar.web.config.SoarActionConnectorProperties;
import com.socp.soar.web.config.SoarRuntimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Playbook lifecycle and verified response-action execution service. */
@SpringBootApplication(scanBasePackages = {"com.socp.soar.web", "com.socp.platform"})
@EnableConfigurationProperties({SoarActionConnectorProperties.class, SoarRuntimeProperties.class})
public class SoarWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(SoarWebApplication.class, args);
    }
}
