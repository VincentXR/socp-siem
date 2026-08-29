package com.socp.hips.web;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Endpoint registration, heartbeat, and event-ingress service. */
@SpringBootApplication(scanBasePackages = "com.socp.hips.web")
@Import(SocpPlatformAutoConfiguration.class)
public class HipsWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(HipsWebApplication.class, args);
    }
}
