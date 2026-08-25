package com.socp.hips.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Endpoint registration, heartbeat, and event-ingress service. */
@SpringBootApplication(scanBasePackages = {"com.socp.hips.web", "com.socp.platform"})
public class HipsWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(HipsWebApplication.class, args);
    }
}
