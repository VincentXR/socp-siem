package com.socp.ai.service;

import com.socp.ai.model.AiResult;
import com.socp.ai.store.QaEntity;
import com.socp.ai.store.QaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AiAssistantServiceTest {

    @Test
    void loadsPersistedKnowledgeAndReturnsTypedSuggestion() {
        QaRepository repository = mock(QaRepository.class);
        given(repository.findAll()).willReturn(List.of(new QaEntity("credential", "rotate credentials")));
        AiAssistantService service = new AiAssistantService(repository);
        service.init();

        AiResult result = service.ask("credential exposure");

        assertThat(result.question()).isEqualTo("credential exposure");
        assertThat(result.answer()).isEqualTo("rotate credentials");
        assertThat(result.suggestion()).isNotBlank();
        assertThat(result.elapsedMs()).isNotNegative();
    }

    @Test
    void unknownQuestionGetsOperationalFallback() {
        QaRepository repository = mock(QaRepository.class);
        given(repository.findAll()).willReturn(List.of(new QaEntity("known", "answer")));
        AiAssistantService service = new AiAssistantService(repository);
        service.init();

        AiResult result = service.ask("unmapped subject");

        assertThat(result.answer()).contains("unmapped subject");
        assertThat(result.suggestion()).isNotBlank();
    }
}
