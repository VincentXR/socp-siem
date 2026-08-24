package com.socp.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM client configuration properties (compatible with OpenAI, Ollama, DeepSeek, vLLM, etc.).
 */
@Component
@ConfigurationProperties(prefix = "socp.ai.llm")
public class LlmProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:11434";
    private String apiKey = "";
    private String model = "qwen2.5:7b";
    private int timeoutMs = 10000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
