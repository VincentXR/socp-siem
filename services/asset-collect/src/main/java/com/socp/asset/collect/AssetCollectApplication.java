package com.socp.asset.collect;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.socp.asset.collect.config.AssetCollectProperties;
import org.springframework.context.annotation.Import;

/** Standalone compatibility launcher for durable asset collection. */
@SpringBootApplication(scanBasePackages = "com.socp.asset.collect")
@EnableConfigurationProperties(AssetCollectProperties.class)
@Import(SocpPlatformAutoConfiguration.class)
public class AssetCollectApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetCollectApplication.class, args);
    }
}
