package com.socp.threat.web;

import com.socp.platform.starter.EnableSocpPlatformJpa;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 威胁情报服务（TIM-2）。context-path=/threat-web。
 * 实体 IocEntity 继承平台 {@code BaseEntity}（提供 tenant_id/created_at/updated_at）。
 * 历史注释断言"{@code @EntityScan} 必须显式带上 com.socp.platform，否则基类不会被纳入
 * 持久化单元"——该说法不成立：Hibernate 沿 Java 父类链绑定 {@code @MappedSuperclass}，
 * 不依赖包扫描（platform/socp-starter 的 SocpPlatformJpaConfigurationTest 锁住了这点）。
 * 扫描范围现在统一由 {@code @EnableSocpPlatformJpa} 提供，见 docs/adding-a-service.md。
 */
@SpringBootApplication(scanBasePackages = "com.socp.threat.web")
@EnableSocpPlatformJpa
@Import(SocpPlatformAutoConfiguration.class)
public class ThreatWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(ThreatWebApplication.class, args);
    }
}
