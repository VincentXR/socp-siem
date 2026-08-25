package com.socp.ai.infrastructure.llm;

import com.socp.ai.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    private final HttpClient httpClient;

    public HttpLlmChatClient(LlmProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
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
        try {
            String url = normalizeBaseUrl(properties.getBaseUrl()) + "/v1/chat/completions";
            var requestBody = Map.of(
                    "model", properties.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", question)
                    ),
                    "temperature", 0.3
            );
            String json = MAPPER.writeValueAsString(requestBody);

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + properties.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = MAPPER.readTree(response.body());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    String content = choices.get(0).path("message").path("content").asText();
                    if (content != null && !content.isBlank()) {
                        return Optional.of(content.trim());
                    }
                }
            } else {
                log.warn("LLM API returned status={} body={}", response.statusCode(), response.body());
            }
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
