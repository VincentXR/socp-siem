package com.socp.alert;

import com.socp.alert.config.AlertDeliveryProperties;
import com.socp.alert.config.AlertEnrichmentProperties;
import com.socp.alert.config.AlertKafkaProperties;
import com.socp.alert.config.AlertOutboxProperties;
import com.socp.alert.config.ClickHouseProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Import;

/**
 * ALERT 告警管理服务（SSAP5）。context-path=/alert-web，容器内 8080。
 * 领域代码只扫描 com.socp.alert；共享横切通过 socp-starter 显式装配。
 */
@SpringBootApplication(scanBasePackages = "com.socp.alert")
@EntityScan(basePackages = {"com.socp.alert", "com.socp.platform"})
@EnableJpaRepositories(basePackages = "com.socp.alert")
@EnableConfigurationProperties({ClickHouseProperties.class, AlertDeliveryProperties.class,
        AlertOutboxProperties.class, AlertEnrichmentProperties.class, AlertKafkaProperties.class})
@org.springframework.scheduling.annotation.EnableScheduling
@Import(SocpPlatformAutoConfiguration.class)
public class AlertWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlertWebApplication.class, args);
    }
}
