package com.socp.soc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** SocBase（soc-base）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.soc", "com.socp.platform"})
public class SocBaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocBaseApplication.class, args);
    }
}
