package com.socp.report.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ReportWeb（report-web）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.report.web", "com.socp.platform"})
public class ReportWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportWebApplication.class, args);
    }
}
