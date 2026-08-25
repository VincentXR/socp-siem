package com.socp.hips.collect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.socp.hips.collect.config.HipsCollectProperties;

/** HipsCollect（hips-collect）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.hips.collect", "com.socp.platform"})
@EnableConfigurationProperties(HipsCollectProperties.class)
public class HipsCollectApplication {
    public static void main(String[] args) {
        SpringApplication.run(HipsCollectApplication.class, args);
    }
}
