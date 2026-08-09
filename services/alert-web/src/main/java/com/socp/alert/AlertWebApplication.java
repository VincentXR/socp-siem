package com.socp.alert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * ALERT 告警管理服务（SSAP5）。context-path=/alert-web，容器内 8080。
 * 扫描 com.socp.platform 以装配横切（tenant/auth/audit/obs/ratelimit/error/data）。
 */
@SpringBootApplication(scanBasePackages = {"com.socp.alert", "com.socp.platform"})
@EntityScan(basePackages = {"com.socp.alert", "com.socp.platform"})
@EnableJpaRepositories(basePackages = "com.socp.alert")
public class AlertWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlertWebApplication.class, args);
    }
}
