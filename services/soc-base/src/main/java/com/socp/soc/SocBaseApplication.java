package com.socp.soc;

import com.socp.soc.config.KafkaAuditProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Tenant, compliance, audit, and platform metadata service. */
@SpringBootApplication(scanBasePackages = "com.socp.soc")
@EnableConfigurationProperties(KafkaAuditProperties.class)
@Import(SocpPlatformAutoConfiguration.class)
public class SocBaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocBaseApplication.class, args);
    }
}
