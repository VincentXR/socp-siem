package com.socp.ai;

import com.socp.ai.config.AiRuntimeProperties;
import com.socp.ai.config.InvestigationProperties;
import com.socp.platform.starter.SocpPlatformAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Preview security knowledge and optional LLM assistant service. */
@SpringBootApplication(scanBasePackages = "com.socp.ai")
@EnableConfigurationProperties({AiRuntimeProperties.class, InvestigationProperties.class})
@Import(SocpPlatformAutoConfiguration.class)
public class AiAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}
