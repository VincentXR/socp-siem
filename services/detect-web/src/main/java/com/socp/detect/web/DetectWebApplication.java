package com.socp.detect.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Rule evaluation, detection state, and durable alert hand-off service. */
@SpringBootApplication(scanBasePackages = {"com.socp.detect.web", "com.socp.platform"})
@EnableScheduling
public class DetectWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectWebApplication.class, args);
    }
}
