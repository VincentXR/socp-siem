package com.socp.hips.collect;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.socp.hips.collect.config.HipsCollectProperties;
import org.springframework.context.annotation.Import;

/** Standalone compatibility launcher for durable endpoint collection. */
@SpringBootApplication(scanBasePackages = "com.socp.hips.collect")
@EnableConfigurationProperties(HipsCollectProperties.class)
@Import(SocpPlatformAutoConfiguration.class)
public class HipsCollectApplication {
    public static void main(String[] args) {
        SpringApplication.run(HipsCollectApplication.class, args);
    }
}
