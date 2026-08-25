package com.socp.asset.collect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.socp.asset.collect.config.AssetCollectProperties;

/** Standalone compatibility launcher for durable asset collection. */
@SpringBootApplication(scanBasePackages = {"com.socp.asset.collect", "com.socp.platform"})
@EnableConfigurationProperties(AssetCollectProperties.class)
public class AssetCollectApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetCollectApplication.class, args);
    }
}
