package com.socp.report.web;

import com.socp.report.web.config.ClickHouseProperties;
import com.socp.report.web.config.ReportObjectStorageProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Reporting and object-storage service. */
@SpringBootApplication(
        scanBasePackages = "com.socp.report.web",
        exclude = {
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class
        })
@EnableConfigurationProperties({ClickHouseProperties.class, ReportObjectStorageProperties.class})
@Import(SocpPlatformAutoConfiguration.class)
public class ReportWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportWebApplication.class, args);
    }
}
