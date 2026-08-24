package com.socp.ai.service;

import com.socp.ai.model.AiResult;
import com.socp.ai.model.AiResponseSource;
import com.socp.ai.store.QaEntity;
import com.socp.ai.store.QaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

class AiAssistantServiceTest {

    @Test
    void loadsPersistedKnowledgeAndReturnsTypedSuggestion() {
        QaRepository repository = mock(QaRepository.class);
        given(repository.count()).willReturn(1L);
        given(repository.findMatches(eq("credential exposure"), any(Pageable.class)))
                .willReturn(List.of(new QaEntity("credential", "rotate credentials")));
        AiAssistantService service = new AiAssistantService(repository);
        service.init();

        AiResult result = service.ask("credential exposure");

        assertThat(result.question()).isEqualTo("credential exposure");
        assertThat(result.answer()).isEqualTo("rotate credentials");
        assertThat(result.suggestion()).isNotBlank();
        assertThat(result.elapsedMs()).isNotNegative();
        assertThat(result.source()).isEqualTo(AiResponseSource.KNOWLEDGE_BASE);
    }

    @Test
    void unknownQuestionGetsOperationalFallback() {
        QaRepository repository = mock(QaRepository.class);
        given(repository.count()).willReturn(1L);
        given(repository.findMatches(eq("unmapped subject"), any(Pageable.class))).willReturn(List.of());
        AiAssistantService service = new AiAssistantService(repository);
        service.init();

        AiResult result = service.ask("unmapped subject");

        assertThat(result.answer()).contains("unmapped subject");
        assertThat(result.source()).isEqualTo(AiResponseSource.FALLBACK);
        assertThat(result.suggestion()).isNotBlank();
    }

    @Test
    void prefersLlmResponseWhenEnabled() {
        QaRepository repository = mock(QaRepository.class);
        com.socp.ai.llm.LlmChatClient llmClient = mock(com.socp.ai.llm.LlmChatClient.class);
        given(llmClient.isEnabled()).willReturn(true);
        given(llmClient.chat("如何检测 SQL 注入？")).willReturn(java.util.Optional.of("LLM 智能研判：使用正则匹配 union select"));

        AiAssistantService service = new AiAssistantService(repository, llmClient);
        AiResult result = service.ask("如何检测 SQL 注入？");

        assertThat(result.question()).isEqualTo("如何检测 SQL 注入？");
        assertThat(result.answer()).isEqualTo("LLM 智能研判：使用正则匹配 union select");
        assertThat(result.suggestion()).contains("AI 专家大模型");
        assertThat(result.source()).isEqualTo(AiResponseSource.LLM);
    }

    @Test
    void fallsBackToKnowledgeBaseWhenLlmReturnsEmpty() {
        QaRepository repository = mock(QaRepository.class);
        com.socp.ai.llm.LlmChatClient llmClient = mock(com.socp.ai.llm.LlmChatClient.class);
        given(llmClient.isEnabled()).willReturn(true);
        given(llmClient.chat("暴力破解")).willReturn(java.util.Optional.empty());
        given(repository.findMatches(eq("暴力破解"), any(Pageable.class)))
                .willReturn(List.of(new QaEntity("暴力破解", "配置 AUTH-BRUTE 规则")));

        AiAssistantService service = new AiAssistantService(repository, llmClient);
        AiResult result = service.ask("暴力破解");

        assertThat(result.question()).isEqualTo("暴力破解");
        assertThat(result.answer()).isEqualTo("配置 AUTH-BRUTE 规则");
        assertThat(result.source()).isEqualTo(AiResponseSource.KNOWLEDGE_BASE);
    }
}
