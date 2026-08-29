package com.socp.notify.web;

import com.socp.notify.web.config.NotifySmtpProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.socp.notify.web")
@EnableConfigurationProperties(NotifySmtpProperties.class)
@Import(SocpPlatformAutoConfiguration.class)
public class NotifyWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifyWebApplication.class, args);
    }
}
