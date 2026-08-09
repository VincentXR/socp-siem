package com.socp.notify.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.socp.notify.web", "com.socp.platform"})
public class NotifyWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifyWebApplication.class, args);
    }
}
