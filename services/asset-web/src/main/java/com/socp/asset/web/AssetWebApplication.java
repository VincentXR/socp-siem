package com.socp.asset.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** AssetWeb（asset-web）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.asset.web", "com.socp.platform"})
public class AssetWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetWebApplication.class, args);
    }
}
