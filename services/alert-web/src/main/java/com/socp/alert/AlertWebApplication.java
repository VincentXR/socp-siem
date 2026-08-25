package com.socp.alert;

import com.socp.alert.config.AlertDeliveryProperties;
import com.socp.alert.config.AlertEnrichmentProperties;
import com.socp.alert.config.AlertKafkaProperties;
import com.socp.alert.config.AlertOutboxProperties;
import com.socp.alert.config.ClickHouseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * ALERT 告警管理服务（SSAP5）。context-path=/alert-web，容器内 8080。
 * 扫描 com.socp.platform 以装配横切（tenant/auth/audit/obs/ratelimit/error/data）。
 */
@SpringBootApplication(scanBasePackages = {"com.socp.alert", "com.socp.platform"})
@EntityScan(basePackages = {"com.socp.alert", "com.socp.platform"})
@EnableJpaRepositories(basePackages = "com.socp.alert")
@EnableConfigurationProperties({ClickHouseProperties.class, AlertDeliveryProperties.class,
        AlertOutboxProperties.class, AlertEnrichmentProperties.class, AlertKafkaProperties.class})
@org.springframework.scheduling.annotation.EnableScheduling
public class AlertWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlertWebApplication.class, args);
    }
}
