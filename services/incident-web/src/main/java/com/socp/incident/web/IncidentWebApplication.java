package com.socp.incident.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.socp.incident.web", "com.socp.platform"})
public class IncidentWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(IncidentWebApplication.class, args);
    }
}
