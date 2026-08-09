package com.socp.detect.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** DetectWeb（detect-web）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.detect.web", "com.socp.platform"})
public class DetectWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectWebApplication.class, args);
    }
}
