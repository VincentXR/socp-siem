package com.socp.report.web;

import com.socp.report.web.config.ClickHouseProperties;
import com.socp.report.web.config.ReportObjectStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Reporting and object-storage service. */
@SpringBootApplication(scanBasePackages = {"com.socp.report.web", "com.socp.platform"})
@EnableConfigurationProperties({ClickHouseProperties.class, ReportObjectStorageProperties.class})
public class ReportWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportWebApplication.class, args);
    }
}
