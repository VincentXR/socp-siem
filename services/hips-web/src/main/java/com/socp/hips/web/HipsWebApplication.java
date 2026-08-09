package com.socp.hips.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** HipsWeb（hips-web）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.hips.web", "com.socp.platform"})
public class HipsWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(HipsWebApplication.class, args);
    }
}
