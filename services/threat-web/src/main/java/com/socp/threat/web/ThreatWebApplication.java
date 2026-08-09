package com.socp.threat.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 威胁情报服务（TIM-2）。context-path=/threat-web。
 * @EntityScan 必须显式带上 com.socp.platform：IocEntity 继承了该包下的 @MappedSuperclass
 * BaseEntity（提供 tenant_id/created_at/updated_at），否则基类不会被纳入持久化单元。
 * 一旦显式声明 @EntityScan，仓库扫描范围也一并钉死，避免隐式默认值日后被改动带偏。
 */
@SpringBootApplication(scanBasePackages = {"com.socp.threat.web", "com.socp.platform"})
@EntityScan(basePackages = {"com.socp.threat.web", "com.socp.platform"})
@EnableJpaRepositories(basePackages = "com.socp.threat.web")
public class ThreatWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ThreatWebApplication.class, args);
    }
}
