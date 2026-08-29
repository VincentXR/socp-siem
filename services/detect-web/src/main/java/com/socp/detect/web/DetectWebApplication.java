package com.socp.detect.web;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Rule evaluation, detection state, and durable alert hand-off service. */
@SpringBootApplication(scanBasePackages = "com.socp.detect.web")
@EnableScheduling
@Import(SocpPlatformAutoConfiguration.class)
public class DetectWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectWebApplication.class, args);
    }
}
