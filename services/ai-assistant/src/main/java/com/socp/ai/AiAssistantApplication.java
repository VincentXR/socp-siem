package com.socp.ai;

import com.socp.ai.config.AiRuntimeProperties;
import com.socp.ai.config.InvestigationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Preview security knowledge and optional LLM assistant service. */
@SpringBootApplication(scanBasePackages = {"com.socp.ai", "com.socp.platform"})
@EnableConfigurationProperties({AiRuntimeProperties.class, InvestigationProperties.class})
public class AiAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}
