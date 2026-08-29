package com.socp.attack.web;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.socp.attack.web")
@Import(SocpPlatformAutoConfiguration.class)
public class AttackWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AttackWebApplication.class, args);
    }
}
