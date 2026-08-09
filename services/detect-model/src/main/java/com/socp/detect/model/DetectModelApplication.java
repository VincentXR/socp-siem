package com.socp.detect.model;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** DetectModel（detect-model）。骨架：待按 P 提示词填充业务（见架构报告）。 */
@SpringBootApplication(scanBasePackages = {"com.socp.detect.model", "com.socp.platform"})
public class DetectModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectModelApplication.class, args);
    }
}
