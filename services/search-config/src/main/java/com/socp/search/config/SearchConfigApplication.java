package com.socp.search.config;

import com.socp.platform.starter.EnableSocpPlatformJpa;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import com.socp.search.config.config.ConfigCacheProperties;
import com.socp.search.config.config.IngestLimitsProperties;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.config.KafkaProperties;
import com.socp.search.config.config.OpenSearchIndexerProperties;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.config.SearchCacheProperties;
import com.socp.search.config.config.VectorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Import;

/**
 * SearchConfig（search-config）：日志源配置与检索。
 * 实体继承平台 {@code BaseEntity}（tenant_id/created_at/updated_at）。扫描范围由
 * {@code @EnableSocpPlatformJpa} 统一提供，业务主类不再手写 {@code @EntityScan} /
 * {@code @EnableJpaRepositories}：Hibernate 是沿 Java 父类链绑定
 * {@code @MappedSuperclass} 的，历史注释里"必须显式扫 com.socp.platform"的说法不成立
 * （platform/socp-starter 的 SocpPlatformJpaConfigurationTest 锁住了这一行为）。
 * 配置属性按全仓统一口径显式列在 {@code @EnableConfigurationProperties} 上；原先的
 * {@code @ConfigurationPropertiesScan} 会静默注册扫到的每个类，与 {@code @Component}
 * 型属性类共存时会产出重复 bean 定义（见 docs/adding-a-service.md）。
 */
@SpringBootApplication(scanBasePackages = "com.socp.search.config")
@EnableSocpPlatformJpa
@EnableScheduling
@EnableConfigurationProperties({ConfigCacheProperties.class, IngestLimitsProperties.class,
        IngestRuntimeProperties.class, KafkaProperties.class, OpenSearchIndexerProperties.class,
        OpenSearchProperties.class, SearchCacheProperties.class, VectorProperties.class})
@Import(SocpPlatformAutoConfiguration.class)
public class SearchConfigApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchConfigApplication.class, args);
    }
}
