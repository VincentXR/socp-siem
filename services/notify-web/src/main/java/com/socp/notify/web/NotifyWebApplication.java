package com.socp.notify.web;

import com.socp.notify.web.config.NotifySmtpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.socp.notify.web", "com.socp.platform"})
@EnableConfigurationProperties(NotifySmtpProperties.class)
public class NotifyWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifyWebApplication.class, args);
    }
}
