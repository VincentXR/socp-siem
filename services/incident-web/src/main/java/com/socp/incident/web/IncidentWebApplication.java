package com.socp.incident.web;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.socp.incident.web")
@Import(SocpPlatformAutoConfiguration.class)
public class IncidentWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(IncidentWebApplication.class, args);
    }
}
