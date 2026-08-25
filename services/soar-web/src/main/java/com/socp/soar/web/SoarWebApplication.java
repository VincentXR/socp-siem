package com.socp.soar.web;

import com.socp.soar.web.config.SoarActionConnectorProperties;
import com.socp.soar.web.config.SoarRuntimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** SoarWeb（soar-web）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.soar.web", "com.socp.platform"})
@EnableConfigurationProperties({SoarActionConnectorProperties.class, SoarRuntimeProperties.class})
public class SoarWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(SoarWebApplication.class, args);
    }
}
