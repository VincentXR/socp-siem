package com.socp.ai.domain;

/**
 * AI 助手问答结果。
 */
public record AiResult(
        String question,
        String answer,
        String suggestion,
        long elapsedMs,
        AiResponseSource source
) {
}
