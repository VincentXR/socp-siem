package com.socp.soc;

import com.socp.soc.config.KafkaAuditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** SocBase（soc-base）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.soc", "com.socp.platform"})
@EnableConfigurationProperties(KafkaAuditProperties.class)
public class SocBaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocBaseApplication.class, args);
    }
}
