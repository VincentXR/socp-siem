package com.socp.ai.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
    private List<String> allowedHosts = new ArrayList<>();
    private boolean httpsOnly = true;
    private boolean allowPrivateNetworks;

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

    public List<String> getAllowedHosts() {
        return List.copyOf(allowedHosts);
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }

    public boolean isHttpsOnly() {
        return httpsOnly;
    }

    public void setHttpsOnly(boolean httpsOnly) {
        this.httpsOnly = httpsOnly;
    }

    public boolean isAllowPrivateNetworks() {
        return allowPrivateNetworks;
    }

    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }
}
