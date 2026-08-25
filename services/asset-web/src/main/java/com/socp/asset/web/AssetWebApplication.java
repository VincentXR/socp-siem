package com.socp.asset.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Asset inventory and collection-ingress service. */
@SpringBootApplication(scanBasePackages = {"com.socp.asset.web", "com.socp.platform"})
public class AssetWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetWebApplication.class, args);
    }
}
