package com.socp.ai.infrastructure.llm;

import com.socp.ai.config.LlmProperties;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.http.PinnedHttpTransport;
import com.socp.platform.client.http.ExternalEndpointPolicy;
import com.socp.platform.client.http.PinnedEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Standard OpenAI / Ollama compatible chat completion client for cyber security reasoning.
 */
@Component
public class HttpLlmChatClient implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmChatClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            你是一名专业的 SOCP SIEM 高级安全运营专家与检测工程专家。
            请针对分析师的安全问题进行专业、结构化、清晰的分析与解答。
            要求：
            1. 阐明攻击机理与 MITRE ATT&CK 战术/技术对齐；
            2. 提供在 SOCP 中的检测规则配置思路（模式/阈值/关联规则）；
            3. 提供在 SEARCH 中用于调查取证的 SPL 检索样例或关键词；
            4. 给出 SOAR 自动化响应剧本或应急处置建议。
            语言简明扼要，排版清晰。
            """;

    private final LlmProperties properties;
    private final ExternalEndpointPolicy endpointPolicy;
    private final PinnedHttpTransport httpClient;

    @Autowired
    public HttpLlmChatClient(LlmProperties properties, ExternalEndpointPolicy endpointPolicy) {
        this.properties = properties;
        this.endpointPolicy = endpointPolicy;
        this.httpClient = new PinnedHttpTransport();
    }

    /** Source-compatible constructor for isolated callers and unit tests. */
    public HttpLlmChatClient(LlmProperties properties) {
        this(properties, new ExternalEndpointPolicy(new SocpClientProperties()));
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public Optional<String> chat(String question) {
        if (!properties.isEnabled() || question == null || question.isBlank()) {
            return Optional.empty();
        }
        String url = normalizeBaseUrl(properties.getBaseUrl()) + "/v1/chat/completions";
        // Carry the validated addresses to the connection manager; retain original hostname for TLS.
        try (PinnedEndpoint pinned = endpointPolicy.validatePinned(url,
                properties.getAllowedHosts(), properties.isHttpsOnly(), properties.isAllowPrivateNetworks())) {
            if (pinned.isRejected()) {
                log.warn("LLM endpoint blocked by outbound policy: {}", pinned.rejectionReason());
                return Optional.empty();
            }
            var requestBody = Map.of(
                    "model", properties.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", question)
                    ),
                    "temperature", 0.3
            );
            String json = MAPPER.writeValueAsString(requestBody);

            if (json.length() > 1_048_576) return Optional.empty();
            byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (payload.length > 1_048_576) return Optional.empty();
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                headers.put("Authorization", "Bearer " + properties.getApiKey());
            }
            var response = httpClient.send("POST", URI.create(url), payload, "application/json", headers, pinned,
                    properties.getTimeoutMs(), properties.getTimeoutMs(), SocpClientProperties.DEFAULT_RESPONSE_BODY_LIMIT_BYTES);
            if (response.status() >= 200 && response.status() < 300) {
                JsonNode root = MAPPER.readTree(response.body());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    String content = choices.get(0).path("message").path("content").asText();
                    if (content != null && !content.isBlank()) {
                        return Optional.of(content.trim());
                    }
                }
            } else {
                log.warn("LLM API returned status={}", response.status());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("LLM API invocation failed, falling back to local security knowledge base: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "http://localhost:11434";
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) return trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
}
