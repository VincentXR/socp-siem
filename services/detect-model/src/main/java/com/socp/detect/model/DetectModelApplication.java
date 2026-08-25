package com.socp.detect.model;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Secondary alert analysis and correlation service. */
@SpringBootApplication(scanBasePackages = {"com.socp.detect.model", "com.socp.platform"})
public class DetectModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectModelApplication.class, args);
    }
}
