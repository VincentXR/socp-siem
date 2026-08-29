package com.socp.detect.model;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Secondary alert analysis and correlation service. */
@SpringBootApplication(scanBasePackages = "com.socp.detect.model")
@Import(SocpPlatformAutoConfiguration.class)
public class DetectModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectModelApplication.class, args);
    }
}
