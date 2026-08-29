package com.socp.report.web;

import com.socp.report.web.config.ClickHouseProperties;
import com.socp.report.web.config.ReportObjectStorageProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Reporting and object-storage service. */
@SpringBootApplication(scanBasePackages = "com.socp.report.web")
@EnableConfigurationProperties({ClickHouseProperties.class, ReportObjectStorageProperties.class})
@Import(SocpPlatformAutoConfiguration.class)
public class ReportWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportWebApplication.class, args);
    }
}
