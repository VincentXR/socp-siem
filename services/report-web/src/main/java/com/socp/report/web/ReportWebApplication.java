package com.socp.report.web;

import com.socp.report.web.config.ClickHouseProperties;
import com.socp.report.web.config.ReportObjectStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** ReportWeb（report-web）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.report.web", "com.socp.platform"})
@EnableConfigurationProperties({ClickHouseProperties.class, ReportObjectStorageProperties.class})
public class ReportWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportWebApplication.class, args);
    }
}
