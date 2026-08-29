package com.socp.asset.web;

import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Asset inventory and collection-ingress service. */
@SpringBootApplication(scanBasePackages = "com.socp.asset.web")
@Import(SocpPlatformAutoConfiguration.class)
public class AssetWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetWebApplication.class, args);
    }
}
