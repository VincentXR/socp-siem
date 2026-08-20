package com.socp.search.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SearchConfig（search-config）：日志源配置与检索。
 * @EntityScan 必须显式带上 com.socp.platform：SearchEventEntity 继承了该包下的
 * @MappedSuperclass BaseEntity（提供 tenant_id/created_at/updated_at）。
 */
@SpringBootApplication(scanBasePackages = {"com.socp.search.config", "com.socp.platform"})
@EntityScan(basePackages = {"com.socp.search.config", "com.socp.platform"})
@EnableJpaRepositories(basePackages = "com.socp.search.config")
@EnableScheduling
public class SearchConfigApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchConfigApplication.class, args);
    }
}
