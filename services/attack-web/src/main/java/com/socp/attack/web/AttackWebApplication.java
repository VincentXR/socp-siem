package com.socp.attack.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.socp.attack.web", "com.socp.platform"})
public class AttackWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AttackWebApplication.class, args);
    }
}
