package com.socp.ai.infrastructure.llm;

import java.util.Optional;

/**
 * Interface for LLM chat completions.
 */
public interface LlmChatClient {

    /** Whether LLM integration is enabled in configuration. */
    boolean isEnabled();

    /** Sends a question to the LLM model and returns the response if successful. */
    Optional<String> chat(String question);
}
