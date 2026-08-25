package com.socp.ai.domain;

/** Identifies which capability produced an assistant response. */
public enum AiResponseSource {
    LLM,
    KNOWLEDGE_BASE,
    FALLBACK
}
